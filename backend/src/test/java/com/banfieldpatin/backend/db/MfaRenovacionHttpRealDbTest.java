package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.jayway.jsonpath.JsonPath;

/**
 * Renovacion de la sesion de MFA pendiente en el enrolamiento, por HTTP REAL (Tomcat en puerto aleatorio,
 * {@link HttpClient} con {@link CookieManager}, PostgreSQL 17 descartable, TOTP calculado por
 * {@link TotpIndependiente}).
 * <p>
 * Para no esperar minutos reales ni depender de la velocidad de la maquina, el reloj de la aplicacion es un
 * {@link RelojControlable}: queda quieto y solo avanza cuando el test lo indica, y TODO el servidor (emision y
 * validacion de JWT, verificacion de TOTP) lo usa. La duracion pendiente se fija en PT1M (el minimo permitido); el
 * validador de JWT conserva su margen de 60 s, de modo que un token vence "de verdad" a {@code exp + 60 s}.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({ "test", "e2e" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MfaRenovacionHttpRealDbTest extends BaseDbTest {

	private static final String SLUG = "mfa-renov-" + UUID.randomUUID();
	private static final String PASSWORD = "clave-segura-de-prueba-123";
	private static final String COOKIE_SESION = "BP_SESION";
	private static final long TTL = 60;
	private static final long MARGEN_JWT = 60;

	/** Reloj mutable e inmovil: instant() devuelve siempre el mismo valor hasta que se lo avanza. */
	static final class RelojControlable extends Clock {
		private volatile Instant ahora = Instant.now();

		void reiniciar() {
			ahora = Instant.now();
		}

		void avanzar(Duration d) {
			ahora = ahora.plus(d);
		}

		@Override
		public Instant instant() {
			return ahora;
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}

	@TestConfiguration
	static class RelojDePrueba {
		static final RelojControlable RELOJ = new RelojControlable();

		@Bean
		@Primary
		Clock relojControlable() {
			return RELOJ;
		}
	}

	@DynamicPropertySource
	static void configuracion(DynamicPropertyRegistry registro) {
		registro.add("banfield.escuela.slug", () -> SLUG);
		registro.add("banfield.seguridad.mfa.duracion-pendiente", () -> "PT1M");
	}

	@LocalServerPort
	int puerto;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder passwordEncoder;

	private final RelojControlable reloj = RelojDePrueba.RELOJ;
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
		reloj.reiniciar();
		base = "http://localhost:" + puerto;
		cookies = new CookieManager();
		cliente = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).cookieHandler(cookies).build();
	}

	// ---------- (1) flujo completo con la cookie renovada ----------

	@Test
	void flujoCompletoLoginEnrolarConRenovacionConfirmarMeYAdmin() throws Exception {
		UUID id = crearAdmin("flujo@renov.example");

		HttpResponse<String> login = login("flujo@renov.example");
		String original = valorDeSetCookie(login);
		assertThat(setCookie(login)).contains("Max-Age=" + TTL).contains("HttpOnly");
		// Con la sesion pendiente no hay acceso de ADMIN.
		assertAdminDenegado();

		reloj.avanzar(Duration.ofSeconds(20));
		get("/api/auth/csrf");
		HttpResponse<String> enrolar = post("/api/auth/admin/mfa/enrolar", null);
		assertThat(enrolar.statusCode()).isEqualTo(200);
		String secreto = JsonPath.read(enrolar.body(), "$.secretoBase32");

		// Se emitio una cookie nueva, con los atributos de siempre y vencimiento desde AHORA (+20 s).
		assertThat(setCookie(enrolar)).contains("Max-Age=" + TTL, "HttpOnly", "Path=/", "SameSite=");
		String renovada = valorDeSetCookie(enrolar);
		assertThat(renovada).isNotEqualTo(original);
		String payload = payload(renovada);
		assertThat(JsonPath.<String>read(payload, "$.mfa")).isEqualTo("PENDIENTE");
		assertThat(JsonPath.<Boolean>read(payload, "$.mfa_renovado")).isTrue();
		assertThat(JsonPath.<String>read(payload, "$.rol")).isEqualTo("ADMIN");
		assertThat(JsonPath.<String>read(payload, "$.sub")).isEqualTo(id.toString());
		long emitido = ((Number) JsonPath.read(payload, "$.iat")).longValue();
		long vence = ((Number) JsonPath.read(payload, "$.exp")).longValue();
		assertThat(vence - emitido).isEqualTo(TTL);
		assertThat(emitido).isEqualTo(reloj.instant().getEpochSecond());
		assertThat(payload).doesNotContain(secreto);
		// La renovada tambien es solo pendiente: sin acceso de ADMIN.
		assertAdminDenegado();

		get("/api/auth/csrf");
		HttpResponse<String> confirmar = post("/api/auth/admin/mfa/confirmar", codigo(secreto));
		assertThat(confirmar.statusCode()).isEqualTo(200);
		assertThat(setCookie(confirmar)).contains("Max-Age=28800");
		String completa = payload(valorDeSetCookie(confirmar));
		assertThat(JsonPath.<String>read(completa, "$.mfa")).isEqualTo("COMPLETADA");
		assertThat(completa).doesNotContain("mfa_renovado");

		HttpResponse<String> me = get("/api/auth/me");
		assertThat(JsonPath.<Boolean>read(me.body(), "$.mfaPendiente")).isFalse();
		assertThat(JsonPath.<String>read(me.body(), "$.rol")).isEqualTo("ADMIN");
		assertThat(get("/api/admin/invitaciones").statusCode()).isEqualTo(200);
		assertThat(accionesDe(id)).contains("MFA_ENROLADO", "MFA_CONFIRMADO").doesNotContain("MFA_FALLO");
	}

	// ---------- (2) escenario clave: token a punto de vencer ----------

	@Test
	void unTokenPendienteCercaDeVencerPuedeEnrolarYObtieneUnaVentanaNuevaParaConfirmar() throws Exception {
		UUID id = crearAdmin("clave@renov.example");
		String original = valorDeSetCookie(login("clave@renov.example"));

		// 55 s despues del login: al token original le quedan 5 s.
		reloj.avanzar(Duration.ofSeconds(55));
		get("/api/auth/csrf");
		HttpResponse<String> enrolar = post("/api/auth/admin/mfa/enrolar", null);
		assertThat(enrolar.statusCode()).isEqualTo(200);
		String secreto = JsonPath.read(enrolar.body(), "$.secretoBase32");

		// Se instala y escanea la app: pasan 90 s. t = login + 145 s, ya mas alla de exp original + margen (120 s),
		// pero dentro de la ventana renovada (login + 55 + 60 = 115 s, +60 s de margen = 175 s).
		reloj.avanzar(Duration.ofSeconds(90));
		assertThat(sinJar(() -> getConCookie("/api/auth/me", original)).statusCode())
				.as("el token ORIGINAL ya esta vencido incluso con el margen").isEqualTo(401);

		get("/api/auth/csrf");
		HttpResponse<String> confirmar = post("/api/auth/admin/mfa/confirmar", codigo(secreto));
		assertThat(confirmar.statusCode()).as("la ventana renovada permite confirmar").isEqualTo(200);
		assertThat(mfaHabilitado(id)).isTrue();
		assertThat(accionesDe(id)).doesNotContain("MFA_FALLO");
		assertThat(get("/api/admin/invitaciones").statusCode()).isEqualTo(200);
	}

	// ---------- (3) tambien la ventana renovada vence; un nuevo login recupera ----------

	@Test
	void vencidaLaVentanaRenovadaConfirmarDa401NoAutenticadoSinMfaFalloYUnNuevoLoginRecupera() throws Exception {
		UUID id = crearAdmin("lapso@renov.example");
		login("lapso@renov.example");
		reloj.avanzar(Duration.ofSeconds(10));
		get("/api/auth/csrf");
		String secreto = JsonPath.read(post("/api/auth/admin/mfa/enrolar", null).body(), "$.secretoBase32");

		// La renovada vence a 10 + 60 = 70 s; con el margen, a 130 s. Se pasa por 1 s.
		reloj.avanzar(Duration.ofSeconds(TTL + MARGEN_JWT + 1));
		get("/api/auth/csrf");
		HttpResponse<String> tarde = post("/api/auth/admin/mfa/confirmar", codigo(secreto));
		assertThat(tarde.statusCode()).isEqualTo(401);
		assertThat(JsonPath.<String>read(tarde.body(), "$.codigo")).isEqualTo("NO_AUTENTICADO");
		assertThat(accionesDe(id)).contains("MFA_ENROLADO").doesNotContain("MFA_FALLO", "MFA_CONFIRMADO");
		assertThat(mfaHabilitado(id)).isFalse();

		// Recuperacion: nuevo login + enrolar (reemplaza el secreto sin confirmar) + confirmar.
		nuevoNavegadorSinReiniciarReloj();
		login("lapso@renov.example");
		get("/api/auth/csrf");
		String nuevo = JsonPath.read(post("/api/auth/admin/mfa/enrolar", null).body(), "$.secretoBase32");
		assertThat(nuevo).isNotEqualTo(secreto);
		get("/api/auth/csrf");
		assertThat(post("/api/auth/admin/mfa/confirmar", codigo(nuevo)).statusCode()).isEqualTo(200);
		assertThat(mfaHabilitado(id)).isTrue();
	}

	// ---------- (4) la renovacion es una sola por inicio de sesion ----------

	@Test
	void enrolarConElTokenRenovadoReEnrolaPeroNoExtiendeLaVentana() throws Exception {
		UUID id = crearAdmin("tope@renov.example");
		login("tope@renov.example");
		reloj.avanzar(Duration.ofSeconds(10));
		get("/api/auth/csrf");
		HttpResponse<String> primero = post("/api/auth/admin/mfa/enrolar", null);
		String secreto1 = JsonPath.read(primero.body(), "$.secretoBase32");
		String renovada = valorDeSetCookie(primero);
		assertThat(setCookie(primero)).isNotEmpty();

		// Segundo enrolar con el token ya renovado: 200, secreto nuevo, SIN Set-Cookie de sesion.
		reloj.avanzar(Duration.ofSeconds(30));
		get("/api/auth/csrf");
		HttpResponse<String> segundo = post("/api/auth/admin/mfa/enrolar", null);
		assertThat(segundo.statusCode()).isEqualTo(200);
		assertThat(setCookie(segundo)).as("un token renovado no se renueva otra vez").isEmpty();
		String secreto2 = JsonPath.read(segundo.body(), "$.secretoBase32");
		assertThat(secreto2).isNotEqualTo(secreto1);
		// El navegador conserva la misma cookie renovada: vence a login + 10 + 60 = 70 s.
		assertThat(cookieDe(COOKIE_SESION).getValue()).isEqualTo(renovada);

		// A los 131 s del login (renovada + margen vencidos) ya no sirve, por mas que se haya llamado a enrolar dos veces.
		reloj.avanzar(Duration.ofSeconds(10 + TTL + MARGEN_JWT + 1 - 40));
		get("/api/auth/csrf");
		HttpResponse<String> tarde = post("/api/auth/admin/mfa/confirmar", codigo(secreto2));
		assertThat(tarde.statusCode()).isEqualTo(401);
		assertThat(JsonPath.<String>read(tarde.body(), "$.codigo")).isEqualTo("NO_AUTENTICADO");
		assertThat(mfaHabilitado(id)).isFalse();
	}

	@Test
	void elReEnrolamientoDentroDeLaVentanaRenovadaSigueSiendoConfirmable() throws Exception {
		UUID id = crearAdmin("reenrola@renov.example");
		login("reenrola@renov.example");
		get("/api/auth/csrf");
		String secreto1 = JsonPath.read(post("/api/auth/admin/mfa/enrolar", null).body(), "$.secretoBase32");
		reloj.avanzar(Duration.ofSeconds(20));
		get("/api/auth/csrf");
		String secreto2 = JsonPath.read(post("/api/auth/admin/mfa/enrolar", null).body(), "$.secretoBase32");

		get("/api/auth/csrf");
		// El secreto anterior quedo reemplazado: su codigo no sirve; el nuevo si.
		HttpResponse<String> viejo = post("/api/auth/admin/mfa/confirmar", codigo(secreto1));
		assertThat(viejo.statusCode()).isEqualTo(401);
		assertThat(JsonPath.<String>read(viejo.body(), "$.codigo")).isEqualTo("CODIGO_MFA_INVALIDO");
		get("/api/auth/csrf");
		assertThat(post("/api/auth/admin/mfa/confirmar", codigo(secreto2)).statusCode()).isEqualTo(200);
		assertThat(mfaHabilitado(id)).isTrue();
	}

	// ---------- (5) enrolar fallido no emite cookie ----------

	@Test
	void enrolarSobreMfaYaConfirmadoNoEmiteCookieRenovada() throws Exception {
		crearAdmin("confirmado@renov.example");
		login("confirmado@renov.example");
		get("/api/auth/csrf");
		String secreto = JsonPath.read(post("/api/auth/admin/mfa/enrolar", null).body(), "$.secretoBase32");
		get("/api/auth/csrf");
		assertThat(post("/api/auth/admin/mfa/confirmar", codigo(secreto)).statusCode()).isEqualTo(200);

		// Con sesion completa enrolar da 403 (ruta solo para MFA pendiente) y no emite ninguna cookie.
		get("/api/auth/csrf");
		HttpResponse<String> r = post("/api/auth/admin/mfa/enrolar", null);
		assertThat(r.statusCode()).isEqualTo(403);
		assertThat(setCookie(r)).isEmpty();
	}

	// ---------- utilidades ----------

	private void nuevoNavegadorSinReiniciarReloj() {
		cookies = new CookieManager();
		cliente = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).cookieHandler(cookies).build();
	}

	private void assertAdminDenegado() throws Exception {
		HttpResponse<String> r = get("/api/admin/invitaciones");
		assertThat(r.statusCode()).isEqualTo(403);
		assertThat(JsonPath.<String>read(r.body(), "$.codigo")).isEqualTo("ACCESO_DENEGADO");
	}

	private HttpResponse<String> login(String email) throws Exception {
		get("/api/auth/csrf");
		HttpResponse<String> r = post("/api/auth/admin/login",
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
		assertThat(r.statusCode()).isEqualTo(200);
		assertThat(JsonPath.<Boolean>read(r.body(), "$.mfaPendiente")).isTrue();
		return r;
	}

	/** Codigo TOTP del instante del reloj de la aplicacion (no del reloj de pared). */
	private String codigo(String secreto) {
		return "{\"codigo\":\"" + TotpIndependiente.codigoDePaso(secreto, reloj.instant().getEpochSecond() / 30) + "\"}";
	}

	private UUID crearAdmin(String email) {
		UUID id = datos.admin(escuelaId, email, true);
		jdbc.sql("UPDATE gestion_patin.usuario SET password_hash = :h WHERE id = :id")
				.param("h", passwordEncoder.encode(PASSWORD)).param("id", id).update();
		return id;
	}

	private static String payload(String jwt) {
		return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
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

	/** Set-Cookie completo de la sesion en la respuesta, o cadena vacia si no hay. */
	private static String setCookie(HttpResponse<String> r) {
		return r.headers().allValues("set-cookie").stream().filter(s -> s.startsWith(COOKIE_SESION + "=")).findFirst()
				.orElse("");
	}

	private static String valorDeSetCookie(HttpResponse<String> r) {
		return setCookie(r).split(";")[0].substring((COOKIE_SESION + "=").length());
	}

	private HttpResponse<String> get(String ruta) throws Exception {
		return cliente.send(HttpRequest.newBuilder(URI.create(base + ruta)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	/** GET con la cookie de sesion indicada y SIN jar (para probar un token concreto, p. ej. el original). */
	private HttpResponse<String> getConCookie(String ruta, String valorSesion) throws Exception {
		return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
				HttpRequest.newBuilder(URI.create(base + ruta)).header("Cookie", COOKIE_SESION + "=" + valorSesion).GET()
						.build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> sinJar(ThrowingSupplier<HttpResponse<String>> accion) throws Exception {
		return accion.get();
	}

	private HttpResponse<String> post(String ruta, String json) throws Exception {
		HttpCookie xsrf = cookieDe("XSRF-TOKEN");
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta))
				.header("X-XSRF-TOKEN", xsrf == null ? "" : xsrf.getValue());
		if (json == null) {
			b.POST(HttpRequest.BodyPublishers.noBody());
		} else {
			b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
		}
		return cliente.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	@FunctionalInterface
	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}
}
