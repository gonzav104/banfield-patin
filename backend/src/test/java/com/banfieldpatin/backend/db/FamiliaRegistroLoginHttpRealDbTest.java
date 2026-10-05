package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.banfieldpatin.backend.familias.invitaciones.GeneradorTokenInvitacion;
import com.jayway.jsonpath.JsonPath;

/**
 * Flujo extremo a extremo por HTTP REAL (Tomcat en puerto aleatorio, PostgreSQL 17 descartable, Flyway V1-V3):
 * ADMIN con MFA completa crea una invitacion con familia nueva -> validacion publica -> registro (sin sesion) ->
 * login de FAMILIA con las credenciales nuevas -> /me -> FAMILIA no accede a /api/admin/** -> segundo uso de la
 * invitacion rechazado de forma uniforme -> revocar una invitacion usada -> la auditoria no filtra token ni clave.
 * <p>
 * Cada actor (ADMIN, FAMILIA, anonimo) tiene su propio {@link HttpClient} con su {@link CookieManager}. El TOTP lo
 * calcula {@link TotpIndependiente} y se usa un solo codigo por ejecucion (el de confirmar).
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({ "test", "e2e" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FamiliaRegistroLoginHttpRealDbTest extends BaseDbTest {

	private static final String SLUG = "e2e-familia-" + UUID.randomUUID();
	private static final String PASSWORD_ADMIN = "clave-admin-de-prueba-123";
	private static final String PASSWORD_FAMILIA = "clave-familia-de-prueba-456";
	private static final String COOKIE_SESION = "BP_SESION";

	@DynamicPropertySource
	static void configuracion(DynamicPropertyRegistry registro) {
		registro.add("banfield.escuela.slug", () -> SLUG);
	}

	@LocalServerPort
	int puerto;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder passwordEncoder;

	private DatosDb datos;
	private UUID escuelaId;
	private String base;

	@BeforeAll
	void crearEscuela() {
		datos = new DatosDb(jdbc);
		escuelaId = datos.escuela(SLUG);
		base = "http://localhost:" + puerto;
	}

	@AfterAll
	void borrarEscuela() {
		datos.limpiarEscuela(escuelaId);
	}

	@Test
	void flujoCompletoAdminInvitaRegistroLoginMeYSegundoUso() throws Exception {
		Navegador admin = new Navegador();
		Navegador anonimo = new Navegador();
		Navegador familia = new Navegador();

		// ---- paso 1: ADMIN con MFA completa ----
		String emailAdmin = "admin-" + UUID.randomUUID() + "@e2e.example";
		UUID adminId = adminConMfa(admin, emailAdmin);
		HttpResponse<String> meAdmin = admin.get("/api/auth/me");
		assertThat(meAdmin.statusCode()).as("paso 1 /me del ADMIN").isEqualTo(200);
		assertThat(JsonPath.<String>read(meAdmin.body(), "$.rol")).as("paso 1 rol").isEqualTo("ADMIN");
		assertThat(JsonPath.<Boolean>read(meAdmin.body(), "$.mfaPendiente")).as("paso 1 mfaPendiente").isFalse();

		// ---- paso 2: ADMIN crea la invitacion con familia nueva (S1) ----
		String nombreFamilia = "Familia E2E " + UUID.randomUUID();
		HttpResponse<String> creada = admin.postCsrf("/api/admin/invitaciones",
				"{\"nuevaFamilia\":{\"nombreReferencia\":\"" + nombreFamilia + "\"}}");
		assertThat(creada.statusCode()).as("paso 2 crear invitacion").isEqualTo(201);
		assertThat(creada.headers().firstValue("cache-control").orElse("")).as("paso 2 cache-control")
				.contains("no-store");
		UUID invitacionId = UUID.fromString(JsonPath.read(creada.body(), "$.id"));
		String token = JsonPath.read(creada.body(), "$.token");
		UUID familiaId = UUID.fromString(JsonPath.read(creada.body(), "$.familia.id"));
		assertThat(JsonPath.<String>read(creada.body(), "$.estado")).as("paso 2 estado").isEqualTo("PENDIENTE");
		assertThat(token).as("paso 2 token de 43 caracteres url-safe").hasSize(43).matches("[A-Za-z0-9_-]{43}");

		String tokenHash = jdbc.sql("SELECT token_hash FROM gestion_patin.invitacion WHERE id = :id")
				.param("id", invitacionId).query(String.class).single();
		assertThat(tokenHash).as("paso 2 solo se guarda el hash").matches("[0-9a-f]{64}").isNotEqualTo(token)
				.isEqualTo(GeneradorTokenInvitacion.sha256Hex(token));
		assertThat(filaInvitacion(invitacionId)).as("paso 2 invitacion pendiente en BD")
				.containsExactly(escuelaId, familiaId, null, false, false);
		assertThat(jdbc.sql("SELECT activa FROM gestion_patin.familia WHERE id = :f AND escuela_id = :e")
				.param("f", familiaId).param("e", escuelaId).query(Boolean.class).single())
				.as("paso 2 familia activa de la escuela").isTrue();
		assertThat(contarAuditoria("FAMILIA_CREADA", null)).as("paso 2 FAMILIA_CREADA").isEqualTo(1);
		assertThat(contarAuditoria("INVITACION_CREADA", null)).as("paso 2 INVITACION_CREADA").isEqualTo(1);

		// ---- paso 3: validacion publica (S2) ----
		HttpResponse<String> validar = anonimo.postCsrf("/api/auth/invitaciones/validar", json("token", token));
		assertThat(validar.statusCode()).as("paso 3 validar").isEqualTo(200);
		assertThat(JsonPath.<Boolean>read(validar.body(), "$.valida")).as("paso 3 valida").isTrue();
		assertThat(JsonPath.<String>read(validar.body(), "$.escuelaNombre")).as("paso 3 escuela")
				.isEqualTo("Escuela " + SLUG);
		assertThat(setCookieSesion(validar)).as("paso 3 validar no emite sesion").isEmpty();

		// ---- paso 4: registro por invitacion: 201 y SIN sesion (S3) ----
		String emailFamilia = "familia-" + UUID.randomUUID() + "@e2e.example";
		HttpResponse<String> registro = anonimo.postCsrf("/api/auth/registro/invitacion",
				registroJson(token, emailFamilia));
		assertThat(registro.statusCode()).as("paso 4 registro").isEqualTo(201);
		assertThat(registro.headers().allValues("set-cookie")).as("paso 4 el registro no emite ninguna cookie de sesion")
				.noneMatch(c -> c.startsWith(COOKIE_SESION + "="));
		assertThat(anonimo.cookie(COOKIE_SESION)).as("paso 4 el jar anonimo no tiene sesion").isNull();
		UUID usuarioId = UUID.fromString(JsonPath.read(registro.body(), "$.id"));
		assertThat(JsonPath.<String>read(registro.body(), "$.rol")).as("paso 4 rol").isEqualTo("FAMILIA");
		assertThat(JsonPath.<String>read(registro.body(), "$.familiaId")).as("paso 4 familiaId")
				.isEqualTo(familiaId.toString());
		assertThat(JsonPath.<String>read(registro.body(), "$.escuelaId")).as("paso 4 escuelaId")
				.isEqualTo(escuelaId.toString());
		assertThat(JsonPath.<Boolean>read(registro.body(), "$.mfaPendiente")).as("paso 4 mfaPendiente").isFalse();
		assertThat(JsonPath.<Boolean>read(registro.body(), "$.mfaEnrolado")).as("paso 4 mfaEnrolado").isFalse();
		assertThat(registro.body()).as("paso 4 el cuerpo no expone la clave").doesNotContain(PASSWORD_FAMILIA)
				.doesNotContainIgnoringCase("password");
		HttpResponse<String> meAnonimo = anonimo.get("/api/auth/me");
		assertThat(meAnonimo.statusCode()).as("paso 4 registrarse no inicia sesion").isEqualTo(401);
		assertThat(JsonPath.<String>read(meAnonimo.body(), "$.codigo")).as("paso 4 codigo").isEqualTo("NO_AUTENTICADO");

		// ---- paso 5: estado en BD tras el registro ----
		var inv = jdbc.sql("SELECT usado_en IS NOT NULL AS usada, usuario_id FROM gestion_patin.invitacion WHERE id = :id")
				.param("id", invitacionId).query((rs, n) -> new Object[] { rs.getBoolean("usada"),
						rs.getObject("usuario_id", UUID.class) })
				.single();
		assertThat(inv[0]).as("paso 5 usado_en seteado").isEqualTo(true);
		assertThat(inv[1]).as("paso 5 usuario_id de la invitacion").isEqualTo(usuarioId);
		var usuario = jdbc.sql("""
				SELECT rol, familia_id, escuela_id, activo, email, password_hash
				FROM gestion_patin.usuario WHERE id = :id
				""").param("id", usuarioId).query((rs, n) -> new Object[] { rs.getString("rol"),
				rs.getObject("familia_id", UUID.class), rs.getObject("escuela_id", UUID.class), rs.getBoolean("activo"),
				rs.getString("email"), rs.getString("password_hash") }).single();
		assertThat(usuario[0]).as("paso 5 rol").isEqualTo("FAMILIA");
		assertThat(usuario[1]).as("paso 5 familia").isEqualTo(familiaId);
		assertThat(usuario[2]).as("paso 5 escuela").isEqualTo(escuelaId);
		assertThat(usuario[3]).as("paso 5 activo").isEqualTo(true);
		assertThat(usuario[4]).as("paso 5 email en minusculas").isEqualTo(emailFamilia.toLowerCase());
		assertThat((String) usuario[5]).as("paso 5 la clave se guarda hasheada").startsWith("{bcrypt}$2")
				.isNotEqualTo(PASSWORD_FAMILIA).doesNotContain(PASSWORD_FAMILIA);
		assertThat(contarAuditoria("REGISTRO_POR_INVITACION", usuarioId)).as("paso 5 REGISTRO_POR_INVITACION")
				.isEqualTo(1);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM gestion_patin.auditoria
				WHERE escuela_id = :e AND accion = 'REGISTRO_POR_INVITACION'
				  AND detalle->>'invitacionId' = :i AND detalle->>'familiaId' = :f
				""").param("e", escuelaId).param("i", invitacionId.toString()).param("f", familiaId.toString())
				.query(Long.class).single()).as("paso 5 detalle con invitacionId y familiaId (detalle jsonb consultable)")
				.isEqualTo(1);

		// ---- paso 6: login de FAMILIA con las credenciales nuevas (REQ-INV-07 S9) ----
		HttpResponse<String> login = familia.postCsrf("/api/auth/login",
				"{\"email\":\"" + emailFamilia + "\",\"password\":\"" + PASSWORD_FAMILIA + "\"}");
		assertThat(login.statusCode()).as("paso 6 login FAMILIA").isEqualTo(200);
		assertThat(setCookieSesion(login)).as("paso 6 cookie de sesion HttpOnly").isNotEmpty().contains("HttpOnly");
		assertThat(JsonPath.<String>read(login.body(), "$.rol")).as("paso 6 rol del login").isEqualTo("FAMILIA");
		assertThat(jdbc.sql("""
				SELECT count(*) FROM gestion_patin.auditoria
				WHERE escuela_id = :e AND accion = 'LOGIN_EXITOSO' AND usuario_id = :u AND detalle->>'canal' = 'FAMILIA'
				""").param("e", escuelaId).param("u", usuarioId).query(Long.class).single())
				.as("paso 6 LOGIN_EXITOSO canal=FAMILIA").isEqualTo(1);

		// ---- paso 7: /me de FAMILIA ----
		HttpResponse<String> me = familia.get("/api/auth/me");
		assertThat(me.statusCode()).as("paso 7 /me").isEqualTo(200);
		assertThat(JsonPath.<String>read(me.body(), "$.rol")).as("paso 7 rol").isEqualTo("FAMILIA");
		assertThat(JsonPath.<String>read(me.body(), "$.familiaId")).as("paso 7 familiaId").isEqualTo(familiaId.toString());
		assertThat(JsonPath.<String>read(me.body(), "$.escuelaId")).as("paso 7 escuelaId").isEqualTo(escuelaId.toString());
		assertThat(JsonPath.<String>read(me.body(), "$.email")).as("paso 7 email").isEqualTo(emailFamilia.toLowerCase());
		assertThat(JsonPath.<Boolean>read(me.body(), "$.mfaPendiente")).as("paso 7 mfaPendiente").isFalse();
		assertThat(JsonPath.<Boolean>read(me.body(), "$.mfaEnrolado")).as("paso 7 mfaEnrolado").isFalse();

		// ---- paso 8: FAMILIA no accede a /api/admin/** (S6) ----
		HttpResponse<String> adminInv = familia.get("/api/admin/invitaciones");
		assertThat(adminInv.statusCode()).as("paso 8 FAMILIA en /api/admin/invitaciones").isEqualTo(403);
		assertThat(JsonPath.<String>read(adminInv.body(), "$.codigo")).as("paso 8 codigo").isEqualTo("ACCESO_DENEGADO");
		assertThat(familia.get("/api/admin/familias").statusCode()).as("paso 8 FAMILIA en /api/admin/familias")
				.isEqualTo(403);

		// ---- paso 9: segundo uso rechazado de forma uniforme (S7, S8) ----
		HttpResponse<String> validar2 = anonimo.postCsrf("/api/auth/invitaciones/validar", json("token", token));
		assertThat(validar2.statusCode()).as("paso 9 segundo validar").isEqualTo(400);
		assertThat(JsonPath.<String>read(validar2.body(), "$.codigo")).as("paso 9 codigo validar")
				.isEqualTo("INVITACION_NO_DISPONIBLE");
		String otroEmail = "otro-" + UUID.randomUUID() + "@e2e.example";
		HttpResponse<String> registro2 = anonimo.postCsrf("/api/auth/registro/invitacion",
				registroJson(token, otroEmail));
		assertThat(registro2.statusCode()).as("paso 9 segundo registro (400 uniforme, no 409)").isEqualTo(400);
		assertThat(JsonPath.<String>read(registro2.body(), "$.codigo")).as("paso 9 codigo registro")
				.isEqualTo("INVITACION_NO_DISPONIBLE");
		assertThat(JsonPath.<String>read(registro2.body(), "$.mensaje"))
				.as("paso 9 el mensaje es el mismo que el de validar")
				.isEqualTo(JsonPath.<String>read(validar2.body(), "$.mensaje"));
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.usuario WHERE lower(email) = :e")
				.param("e", otroEmail.toLowerCase()).query(Long.class).single()).as("paso 9 no se creo otro usuario")
				.isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.usuario WHERE familia_id = :f").param("f", familiaId)
				.query(Long.class).single()).as("paso 9 la familia sigue con un solo usuario").isEqualTo(1);
		assertThat(jdbc.sql("SELECT usuario_id FROM gestion_patin.invitacion WHERE id = :id").param("id", invitacionId)
				.query(UUID.class).single()).as("paso 9 la invitacion no cambio").isEqualTo(usuarioId);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM gestion_patin.auditoria
				WHERE escuela_id = :e AND accion = 'REGISTRO_FALLIDO' AND detalle->>'motivo' = 'INVITACION_NO_DISPONIBLE'
				""").param("e", escuelaId).query(Long.class).single()).as("paso 9 REGISTRO_FALLIDO").isEqualTo(1);

		// ---- paso 10: revocar una invitacion usada (S9) ----
		HttpResponse<String> revocar = admin.postCsrf("/api/admin/invitaciones/" + invitacionId + "/revocar", null);
		assertThat(revocar.statusCode()).as("paso 10 revocar una USADA").isEqualTo(409);
		assertThat(JsonPath.<String>read(revocar.body(), "$.codigo")).as("paso 10 codigo")
				.isEqualTo("INVITACION_NO_REVOCABLE");
		assertThat(jdbc.sql("SELECT revocada_en IS NULL FROM gestion_patin.invitacion WHERE id = :id")
				.param("id", invitacionId).query(Boolean.class).single()).as("paso 10 revocada_en sigue NULL").isTrue();
		assertThat(contarAuditoria("INVITACION_REVOCADA", null)).as("paso 10 no hay INVITACION_REVOCADA").isZero();

		// ---- paso 11: la auditoria no contiene token, su hash ni claves (S10) ----
		assertThat(contarAuditoria(null, null)).as("paso 11 hay filas de auditoria para revisar").isPositive();
		for (String secreto : new String[] { token, tokenHash, PASSWORD_FAMILIA, PASSWORD_ADMIN }) {
			assertThat(jdbc.sql("""
					SELECT count(*) FROM gestion_patin.auditoria a
					WHERE a.escuela_id = :e AND (a.detalle::text LIKE :p OR to_jsonb(a)::text LIKE :p)
					""").param("e", escuelaId).param("p", "%" + secreto + "%").query(Long.class).single())
					.as("paso 11 la auditoria no contiene un secreto").isZero();
		}
		assertThat(adminId).as("paso 11 el actor de las altas es el ADMIN").isNotNull();
		assertThat(contarAuditoria("INVITACION_CREADA", null)).isEqualTo(1);
	}

	// ---------- utilidades ----------

	private static String json(String clave, String valor) {
		return "{\"" + clave + "\":\"" + valor + "\"}";
	}

	private static String registroJson(String token, String email) {
		return "{\"token\":\"" + token + "\",\"nombre\":\"Ana\",\"apellido\":\"Perez\",\"email\":\"" + email
				+ "\",\"password\":\"" + PASSWORD_FAMILIA + "\"}";
	}

	/** Login de ADMIN, enrolamiento y confirmacion de MFA por HTTP; deja al navegador con la sesion completa. */
	private UUID adminConMfa(Navegador n, String email) throws Exception {
		UUID id = datos.admin(escuelaId, email, true);
		jdbc.sql("UPDATE gestion_patin.usuario SET password_hash = :h WHERE id = :id")
				.param("h", passwordEncoder.encode(PASSWORD_ADMIN)).param("id", id).update();

		HttpResponse<String> login = n.postCsrf("/api/auth/admin/login",
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD_ADMIN + "\"}");
		assertThat(login.statusCode()).as("paso 1 login ADMIN").isEqualTo(200);
		assertThat(JsonPath.<Boolean>read(login.body(), "$.mfaPendiente")).as("paso 1 login con MFA pendiente").isTrue();

		HttpResponse<String> enrolar = n.postCsrf("/api/auth/admin/mfa/enrolar", null);
		assertThat(enrolar.statusCode()).as("paso 1 enrolar MFA").isEqualTo(200);
		String secreto = JsonPath.read(enrolar.body(), "$.secretoBase32");

		// Un unico TOTP por ejecucion; el servidor tolera el paso anterior y el siguiente.
		String codigo = TotpIndependiente.codigoDePaso(secreto, Instant.now().getEpochSecond() / 30);
		HttpResponse<String> confirmar = n.postCsrf("/api/auth/admin/mfa/confirmar", json("codigo", codigo));
		assertThat(confirmar.statusCode()).as("paso 1 confirmar MFA").isEqualTo(200);
		return id;
	}

	/** Devuelve [escuela_id, familia_id, usuario_id, usada, revocada]. */
	private Object[] filaInvitacion(UUID invitacionId) {
		return jdbc.sql("""
				SELECT escuela_id, familia_id, usuario_id, usado_en IS NOT NULL AS usada, revocada_en IS NOT NULL AS revocada
				FROM gestion_patin.invitacion WHERE id = :id
				""").param("id", invitacionId)
				.query((rs, n) -> new Object[] { rs.getObject("escuela_id", UUID.class),
						rs.getObject("familia_id", UUID.class), rs.getObject("usuario_id", UUID.class),
						rs.getBoolean("usada"), rs.getBoolean("revocada") })
				.single();
	}

	/** Cuenta filas de auditoria de la escuela; accion y recursoId son filtros opcionales. */
	private long contarAuditoria(String accion, UUID recursoId) {
		return jdbc.sql("""
				SELECT count(*) FROM gestion_patin.auditoria
				WHERE escuela_id = :e AND (CAST(:a AS text) IS NULL OR accion = :a)
				  AND (CAST(:r AS uuid) IS NULL OR recurso_id = :r)
				""").param("e", escuelaId).param("a", accion).param("r", recursoId).query(Long.class).single();
	}

	/** Set-Cookie completo de la sesion en la respuesta, o cadena vacia si no hay. */
	private static String setCookieSesion(HttpResponse<String> r) {
		return r.headers().allValues("set-cookie").stream().filter(s -> s.startsWith(COOKIE_SESION + "=")).findFirst()
				.orElse("");
	}

	/** Un actor: su propio cliente HTTP con su propio jar de cookies. */
	private final class Navegador {
		private final CookieManager cookies = new CookieManager();
		private final HttpClient cliente = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.cookieHandler(cookies).build();

		HttpCookie cookie(String nombre) {
			return cookies.getCookieStore().get(URI.create(base)).stream().filter(c -> c.getName().equals(nombre))
					.findFirst().orElse(null);
		}

		HttpResponse<String> get(String ruta) throws Exception {
			return cliente.send(HttpRequest.newBuilder(URI.create(base + ruta)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
		}

		HttpResponse<String> post(String ruta, String json) throws Exception {
			HttpCookie xsrf = cookie("XSRF-TOKEN");
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).header("X-XSRF-TOKEN",
					xsrf == null ? "" : xsrf.getValue());
			if (json == null) {
				b.POST(HttpRequest.BodyPublishers.noBody());
			} else {
				b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
			}
			return cliente.send(b.build(), HttpResponse.BodyHandlers.ofString());
		}

		/** Re-obtiene el token CSRF antes de cada POST (cambia con cada login/sesion nueva). */
		HttpResponse<String> postCsrf(String ruta, String json) throws Exception {
			get("/api/auth/csrf");
			return post(ruta, json);
		}
	}
}
