package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.jayway.jsonpath.JsonPath;

/**
 * Flujo de ADMIN con MFA por HTTP REAL: Tomcat en un puerto aleatorio, {@link HttpClient} con
 * {@link CookieManager} (semantica de cookies de un cliente real: Max-Age, vencimiento, reemplazo) y PostgreSQL 17
 * descartable. Reproduce la secuencia de {@code scripts/smoke-auth.sh}: GET csrf, login, GET csrf, enrolar, GET csrf,
 * confirmar, GET me. El TOTP lo calcula {@link TotpIndependiente}, no el codigo de produccion.
 * <p>
 * Documenta el token de MFA pendiente: vive {@code duracion-pendiente} (PT5M por defecto) y se renueva una vez al
 * enrolar con exito (la renovacion con reloj controlado esta en {@code MfaRenovacionHttpRealDbTest}); vencido, la
 * cookie la descarta el cliente y el servidor responde 401 NO_AUTENTICADO sin llegar al servicio de MFA, por lo que NO
 * se registra MFA_FALLO.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({ "test", "e2e" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MfaHttpRealDbTest extends BaseDbTest {

	private static final String SLUG = "mfa-http-" + UUID.randomUUID();
	private static final String PASSWORD = "clave-segura-de-prueba-123";
	private static final String COOKIE_SESION = "BP_SESION";

	@DynamicPropertySource
	static void escuelaConfigurada(DynamicPropertyRegistry registro) {
		registro.add("banfield.escuela.slug", () -> SLUG);
	}

	@LocalServerPort
	int puerto;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	JwtEncoder jwtEncoder;

	private DatosDb datos;
	private UUID escuelaId;
	private String base;
	private CookieManager cookies;
	private HttpClient cliente;

	@BeforeAll
	void crearEscuela() {
		datos = new DatosDb(jdbc);
		escuelaId = datos.escuela(SLUG);
	}

	@AfterAll
	void borrarEscuela() {
		datos.limpiarEscuela(escuelaId);
	}

	@BeforeEach
	void nuevoNavegador() {
		base = "http://localhost:" + puerto;
		cookies = new CookieManager();
		cliente = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).cookieHandler(cookies).build();
	}

	// ---------- flujo completo ----------

	@Test
	void flujoCompletoPorHttpRealLoginEnrolarConfirmarYMe() throws Exception {
		UUID id = crearAdmin("completo@http.example");

		get("/api/auth/csrf");
		HttpResponse<String> login = post("/api/auth/admin/login", credenciales("completo@http.example"));
		assertThat(login.statusCode()).isEqualTo(200);
		assertThat(JsonPath.<Boolean>read(login.body(), "$.mfaPendiente")).isTrue();
		// Una sesion pendiente vive solo duracion-pendiente (PT5M por defecto), no las 8 h de una sesion completa.
		assertThat(setCookie(login, COOKIE_SESION)).contains("Max-Age=300").contains("HttpOnly");

		get("/api/auth/csrf");
		HttpResponse<String> enrolar = post("/api/auth/admin/mfa/enrolar", null);
		assertThat(enrolar.statusCode()).isEqualTo(200);
		// Enrolar con exito renueva la cookie pendiente (ventana propia para confirmar); ver MfaRenovacionHttpRealDbTest.
		assertThat(setCookie(enrolar, COOKIE_SESION)).contains("Max-Age=300").contains("HttpOnly");
		String secreto = JsonPath.read(enrolar.body(), "$.secretoBase32");

		get("/api/auth/csrf");
		HttpResponse<String> confirmar = post("/api/auth/admin/mfa/confirmar",
				"{\"codigo\":\"" + TotpIndependiente.codigo(secreto, 0) + "\"}");
		assertThat(confirmar.statusCode()).isEqualTo(200);
		assertThat(setCookie(confirmar, COOKIE_SESION)).contains("Max-Age=28800");

		HttpResponse<String> me = get("/api/auth/me");
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(JsonPath.<Boolean>read(me.body(), "$.mfaPendiente")).isFalse();
		assertThat(JsonPath.<String>read(me.body(), "$.rol")).isEqualTo("ADMIN");
		assertThat(accionesDe(id)).contains("MFA_ENROLADO", "MFA_CONFIRMADO").doesNotContain("MFA_FALLO");
	}

	@Test
	void codigoDelPasoAnteriorOSiguienteConfirmaPorHttpReal() throws Exception {
		for (int desfase : new int[] { -1, 1 }) {
			nuevoNavegador();
			String email = "ventana" + desfase + "@http.example";
			crearAdmin(email);
			String secreto = loginYEnrolar(email);
			get("/api/auth/csrf");
			HttpResponse<String> r = post("/api/auth/admin/mfa/confirmar",
					"{\"codigo\":\"" + TotpIndependiente.codigo(secreto, desfase) + "\"}");
			assertThat(r.statusCode()).as("paso %+d", desfase).isEqualTo(200);
		}
	}

	// ---------- firma del fallo: codigo equivocado vs sesion pendiente vencida ----------

	@Test
	void codigoEquivocadoDa401CodigoMfaInvalidoYDejaFilaMfaFallo() throws Exception {
		UUID id = crearAdmin("malo@http.example");
		String secreto = loginYEnrolar("malo@http.example");
		get("/api/auth/csrf");

		String bueno = TotpIndependiente.codigo(secreto, 0);
		String malo = "%06d".formatted((Integer.parseInt(bueno) + 7) % 1_000_000);
		HttpResponse<String> r = post("/api/auth/admin/mfa/confirmar", "{\"codigo\":\"" + malo + "\"}");

		assertThat(r.statusCode()).isEqualTo(401);
		assertThat(JsonPath.<String>read(r.body(), "$.codigo")).isEqualTo("CODIGO_MFA_INVALIDO");
		assertThat(accionesDe(id)).contains("MFA_FALLO");
	}

	@Test
	void sesionPendienteVencidaDa401NoAutenticadoSinMfaFalloYUnNuevoLoginRecupera() throws Exception {
		UUID id = crearAdmin("vencida@http.example");
		String secreto = loginYEnrolar("vencida@http.example");
		get("/api/auth/csrf");
		String sesionVigente = cookieDe(COOKIE_SESION).getValue();

		// El token pendiente que el navegador conserva vencio (pasado el margen de 60 s del validador JWT).
		String vencido = tokenPendienteVencido(id);
		HttpResponse<String> r = postConCookie("/api/auth/admin/mfa/confirmar",
				"{\"codigo\":\"" + TotpIndependiente.codigo(secreto, 0) + "\"}", COOKIE_SESION + "=" + vencido);

		// Con un codigo CORRECTO: lo rechaza el filtro de bearer (401 NO_AUTENTICADO), no el servicio de MFA.
		assertThat(r.statusCode()).isEqualTo(401);
		assertThat(JsonPath.<String>read(r.body(), "$.codigo")).isEqualTo("NO_AUTENTICADO");
		assertThat(accionesDe(id)).contains("MFA_ENROLADO").doesNotContain("MFA_FALLO", "MFA_CONFIRMADO");
		assertThat(mfaHabilitado(id)).isFalse();

		// Sin cookie (el cliente la descarto al cumplirse su Max-Age) la respuesta es la misma.
		HttpResponse<String> sinCookie = sinSesion(() -> post("/api/auth/admin/mfa/confirmar",
				"{\"codigo\":\"" + TotpIndependiente.codigo(secreto, 0) + "\"}"));
		assertThat(sinCookie.statusCode()).isEqualTo(401);
		assertThat(JsonPath.<String>read(sinCookie.body(), "$.codigo")).isEqualTo("NO_AUTENTICADO");

		// Recuperacion: volver a iniciar sesion y enrolar reemplaza el secreto sin confirmar; el flujo termina bien.
		nuevoNavegador();
		String secretoNuevo = loginYEnrolar("vencida@http.example");
		assertThat(secretoNuevo).isNotEqualTo(secreto);
		get("/api/auth/csrf");
		HttpResponse<String> ok = post("/api/auth/admin/mfa/confirmar",
				"{\"codigo\":\"" + TotpIndependiente.codigo(secretoNuevo, 0) + "\"}");
		assertThat(ok.statusCode()).isEqualTo(200);
		assertThat(mfaHabilitado(id)).isTrue();
		assertThat(sesionVigente).isNotBlank();
	}

	// ---------- utilidades ----------

	private String loginYEnrolar(String email) throws Exception {
		get("/api/auth/csrf");
		assertThat(post("/api/auth/admin/login", credenciales(email)).statusCode()).isEqualTo(200);
		get("/api/auth/csrf");
		HttpResponse<String> enrolar = post("/api/auth/admin/mfa/enrolar", null);
		assertThat(enrolar.statusCode()).isEqualTo(200);
		return JsonPath.read(enrolar.body(), "$.secretoBase32");
	}

	private UUID crearAdmin(String email) {
		UUID id = datos.admin(escuelaId, email, true);
		jdbc.sql("UPDATE gestion_patin.usuario SET password_hash = :h WHERE id = :id")
				.param("h", passwordEncoder.encode(PASSWORD)).param("id", id).update();
		return id;
	}

	private static String credenciales(String email) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
	}

	private String tokenPendienteVencido(UUID usuarioId) {
		Instant emitido = Instant.now().minusSeconds(600);
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer("banfield-patin-backend").subject(usuarioId.toString())
				.issuedAt(emitido).expiresAt(emitido.plusSeconds(300)).id(UUID.randomUUID().toString())
				.claim(ServicioTokens.CLAIM_ROL, "ADMIN").claim(ServicioTokens.CLAIM_ESCUELA_ID, escuelaId.toString())
				.claim(ServicioTokens.CLAIM_MFA, ServicioTokens.MFA_PENDIENTE).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	private List<String> accionesDe(UUID usuarioId) {
		return jdbc.sql("SELECT accion FROM gestion_patin.auditoria WHERE escuela_id = :e AND recurso_id = :u "
				+ "AND accion LIKE 'MFA_%' ORDER BY id").param("e", escuelaId).param("u", usuarioId)
				.query(String.class).list();
	}

	private boolean mfaHabilitado(UUID usuarioId) {
		return jdbc.sql("SELECT mfa_habilitado FROM gestion_patin.usuario WHERE id = :u").param("u", usuarioId)
				.query(Boolean.class).single();
	}

	private HttpCookie cookieDe(String nombre) {
		return cookies.getCookieStore().get(URI.create(base)).stream().filter(c -> c.getName().equals(nombre))
				.findFirst().orElse(null);
	}

	private static String setCookie(HttpResponse<String> r, String nombre) {
		return r.headers().allValues("set-cookie").stream().filter(s -> s.startsWith(nombre + "=")).findFirst()
				.orElse("");
	}

	private HttpResponse<String> get(String ruta) throws Exception {
		return cliente.send(HttpRequest.newBuilder(URI.create(base + ruta)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String ruta, String json) throws Exception {
		return enviar(ruta, json, null);
	}

	private HttpResponse<String> postConCookie(String ruta, String json, String cookie) throws Exception {
		return enviar(ruta, json, cookie);
	}

	private HttpResponse<String> enviar(String ruta, String json, String cookieManual) throws Exception {
		HttpCookie xsrf = cookieDe("XSRF-TOKEN");
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta))
				.header("X-XSRF-TOKEN", xsrf == null ? "" : xsrf.getValue());
		if (json == null) {
			b.POST(HttpRequest.BodyPublishers.noBody());
		} else {
			b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
		}
		if (cookieManual != null) {
			// Reemplaza la cookie de sesion del jar por la indicada, conservando la de CSRF.
			b.header("Cookie", cookieManual + (xsrf == null ? "" : "; XSRF-TOKEN=" + xsrf.getValue()));
		}
		// Con cookie manual se usa un cliente SIN jar: si no, el CookieManager agregaria tambien la sesion vigente.
		HttpClient destino = cookieManual == null ? cliente : HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1).build();
		return destino.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** Ejecuta la peticion como un navegador que ya no tiene la cookie de sesion (la conserva solo la de CSRF). */
	private HttpResponse<String> sinSesion(ThrowingSupplier<HttpResponse<String>> accion) throws Exception {
		HttpCookie sesion = cookieDe(COOKIE_SESION);
		if (sesion != null) {
			cookies.getCookieStore().remove(URI.create(base), sesion);
		}
		return accion.get();
	}

	@FunctionalInterface
	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}
}
