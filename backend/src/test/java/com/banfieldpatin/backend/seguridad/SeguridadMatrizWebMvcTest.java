package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.usuarios.Rol;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import jakarta.servlet.http.Cookie;

@WebMvcTest(controllers = { ControladorSondaSeguridad.class, CsrfController.class })
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class })
@ActiveProfiles("test")
class SeguridadMatrizWebMvcTest {

	static final String COOKIE = "BP_SESION";

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	SeguridadPropiedades propiedades;

	@BeforeEach
	void reiniciar() {
		ControladorSondaSeguridad.INVOCACIONES.set(0);
	}

	private Cookie sesion(Rol rol) {
		UUID familia = rol == Rol.FAMILIA ? UUID.randomUUID() : null;
		return new Cookie(COOKIE, tokens.emitir(UUID.randomUUID(), rol, UUID.randomUUID(), familia));
	}

	/** Obtiene una cookie XSRF-TOKEN real pidiendo GET /api/auth/csrf. */
	private Cookie xsrf() throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
		Cookie c = r.getCookie("XSRF-TOKEN");
		assertThat(c).isNotNull();
		return c;
	}

	private ResultActions postConCsrf(String ruta, Cookie... extra) throws Exception {
		Cookie x = xsrf();
		var req = post(ruta).cookie(x).header("X-XSRF-TOKEN", x.getValue());
		for (Cookie c : extra) {
			req.cookie(x, c);
		}
		return mvc.perform(req);
	}

	// ---------- 401 / 403 ----------

	@Test
	void anonimoRecibe401JsonEnRutasPrivadasYDesconocidas() throws Exception {
		for (String ruta : new String[] { "/api/admin/ping", "/api/auth/me", "/api/inexistente" }) {
			mvc.perform(get(ruta))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
					.andExpect(jsonPath("$.detalles").isArray());
		}
	}

	@Test
	void familiaRecibe403EnAdminYAdminEnFamilia() throws Exception {
		mvc.perform(get("/api/admin/ping").cookie(sesion(Rol.FAMILIA)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		mvc.perform(get("/api/familia/ping").cookie(sesion(Rol.ADMIN)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
	}

	@Test
	void rolCorrectoAccede() throws Exception {
		mvc.perform(get("/api/admin/ping").cookie(sesion(Rol.ADMIN))).andExpect(status().isOk());
		mvc.perform(get("/api/familia/ping").cookie(sesion(Rol.FAMILIA))).andExpect(status().isOk());
	}

	// ---------- CSRF ----------

	@Test
	void postSinCsrfDa403YNoLlegaAlHandler() throws Exception {
		mvc.perform(post("/api/auth/login"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		assertThat(ControladorSondaSeguridad.INVOCACIONES.get()).isZero();
	}

	@Test
	void headerYCookieDistintosDan403() throws Exception {
		mvc.perform(post("/api/auth/login").cookie(xsrf()).header("X-XSRF-TOKEN", "otro-valor"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		assertThat(ControladorSondaSeguridad.INVOCACIONES.get()).isZero();
	}

	@Test
	void csrfValidoProcede() throws Exception {
		postConCsrf("/api/auth/login").andExpect(status().isOk());
		assertThat(ControladorSondaSeguridad.INVOCACIONES.get()).isEqualTo(1);
	}

	@Test
	void getNoRequiereCsrf() throws Exception {
		mvc.perform(get("/api/auth/me").cookie(sesion(Rol.FAMILIA))).andExpect(status().isOk());
	}

	@Test
	void getCsrfEmiteCookieLegibleConAtributos() throws Exception {
		mvc.perform(get("/api/auth/csrf"))
				.andExpect(status().isOk())
				.andExpect(cookie().exists("XSRF-TOKEN"))
				.andExpect(cookie().httpOnly("XSRF-TOKEN", false))
				.andExpect(cookie().path("XSRF-TOKEN", "/"))
				.andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
				.andExpect(jsonPath("$.token").isNotEmpty());
	}

	// ---------- Tokens en cookie ----------

	@Test
	void cookieVencidaEnLoginNoProvoca401() throws Exception {
		Clock pasado = Clock.fixed(Instant.now().minus(Duration.ofHours(30)), ZoneOffset.UTC);
		var clave = new SecretKeySpec(propiedades.jwt().secretoBytes(), "HmacSHA256");
		String vencido = new ServicioTokens(new NimbusJwtEncoder(new ImmutableSecret<>(clave)), propiedades, pasado)
				.emitir(UUID.randomUUID(), Rol.FAMILIA, UUID.randomUUID(), UUID.randomUUID());
		postConCsrf("/api/auth/login", new Cookie(COOKIE, vencido)).andExpect(status().isOk());
	}

	@Test
	void soloHeaderBearerNoAutentica() throws Exception {
		String token = sesion(Rol.ADMIN).getValue();
		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
	}

	@Test
	void cookieManipuladaDeOtraClaveOAlgNoneDa401() throws Exception {
		String valido = sesion(Rol.ADMIN).getValue();
		String manipulado = valido.substring(0, valido.length() - 3) + (valido.endsWith("AAA") ? "BBB" : "AAA");
		var otraClave = new SecretKeySpec("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		String deOtraClave = new ServicioTokens(new NimbusJwtEncoder(new ImmutableSecret<>(otraClave)), propiedades, Clock.systemUTC())
				.emitir(UUID.randomUUID(), Rol.ADMIN, UUID.randomUUID(), null);
		String algNone = b64("{\"alg\":\"none\"}") + "." + b64("{\"sub\":\"" + UUID.randomUUID()
				+ "\",\"rol\":\"ADMIN\",\"escuela_id\":\"" + UUID.randomUUID() + "\"}") + ".";
		for (String malo : new String[] { manipulado, deOtraClave, algNone }) {
			mvc.perform(get("/api/admin/ping").cookie(new Cookie(COOKIE, malo)))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
					.andExpect(content().string(not(containsString("Exception"))));
		}
	}

	// ---------- Logout (autenticado, R4) ----------

	@Test
	void logoutSinCookieDa401YSinCsrfDa403() throws Exception {
		postConCsrf("/api/auth/logout").andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/logout").cookie(sesion(Rol.FAMILIA)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		assertThat(ControladorSondaSeguridad.INVOCACIONES.get()).isZero();
	}

	@Test
	void logoutConCookieYCsrfProcede() throws Exception {
		postConCsrf("/api/auth/logout", sesion(Rol.FAMILIA)).andExpect(status().isNoContent());
	}

	// ---------- MFA de ADMIN (RNF-03) ----------

	private Cookie pendiente() {
		return new Cookie(COOKIE, tokens.emitirMfaPendiente(UUID.randomUUID(), UUID.randomUUID()));
	}

	@Test
	void adminConMfaPendienteNoAccedeANingunaRutaProtegidaSalvoMeLogoutYMfa() throws Exception {
		for (String ruta : new String[] { "/api/admin/ping", "/api/familia/ping", "/api/otra/ruta",
				"/api/inexistente" }) {
			mvc.perform(get(ruta).cookie(pendiente()))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		}
	}

	@Test
	void adminConMfaPendienteVeMeCierraSesionYUsaLasRutasDeMfa() throws Exception {
		mvc.perform(get("/api/auth/me").cookie(pendiente())).andExpect(status().isOk());
		postConCsrf("/api/auth/logout", pendiente()).andExpect(status().isNoContent());
		postConCsrf("/api/auth/admin/mfa/ping", pendiente()).andExpect(status().isOk());
	}

	@Test
	void lasRutasDeMfaExigenCsrfAunConTokenPendiente() throws Exception {
		mvc.perform(post("/api/auth/admin/mfa/ping").cookie(pendiente()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		assertThat(ControladorSondaSeguridad.INVOCACIONES.get()).isZero();
	}

	@Test
	void lasRutasDeMfaRechazanAAdminConSesionCompletaYAFamilia() throws Exception {
		postConCsrf("/api/auth/admin/mfa/ping", sesion(Rol.ADMIN)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		postConCsrf("/api/auth/admin/mfa/ping", sesion(Rol.FAMILIA)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		assertThat(ControladorSondaSeguridad.INVOCACIONES.get()).isZero();
	}

	@Test
	void lasRutasDeMfaRechazanAlAnonimoConUnoYNoLlegaAlHandler() throws Exception {
		postConCsrf("/api/auth/admin/mfa/ping").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		assertThat(ControladorSondaSeguridad.INVOCACIONES.get()).isZero();
	}

	@Test
	void adminConSesionCompletaYFamiliaNoSeVenAfectadosEnSusRutas() throws Exception {
		mvc.perform(get("/api/admin/ping").cookie(sesion(Rol.ADMIN))).andExpect(status().isOk());
		mvc.perform(get("/api/familia/ping").cookie(sesion(Rol.FAMILIA))).andExpect(status().isOk());
		// Rutas fuera de /api/admin y /api/familia: una sesion completa pasa la autorizacion de "anyRequest".
		mvc.perform(get("/api/otra/ruta").cookie(sesion(Rol.ADMIN))).andExpect(status().isOk());
		mvc.perform(get("/api/otra/ruta").cookie(sesion(Rol.FAMILIA))).andExpect(status().isOk());
	}

	private String tokenDeAdmin(String etapa) {
		var clave = new SecretKeySpec(propiedades.jwt().secretoBytes(), "HmacSHA256");
		var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(clave));
		var ahora = Instant.now();
		var claims = JwtClaimsSet.builder()
				.issuer(propiedades.jwt().emisor()).subject(UUID.randomUUID().toString()).issuedAt(ahora)
				.expiresAt(ahora.plusSeconds(600)).claim("rol", "ADMIN").claim("escuela_id", UUID.randomUUID().toString());
		if (etapa != null) {
			claims.claim("mfa", etapa);
		}
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
				.getTokenValue();
	}

	@Test
	void unTokenDeAdminSinEtapaMfaOConEtapaDesconocidaDa401() throws Exception {
		for (String etapa : new String[] { null, "", "OK", "completada" }) {
			mvc.perform(get("/api/admin/ping").cookie(new Cookie(COOKIE, tokenDeAdmin(etapa))))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
			mvc.perform(get("/api/auth/me").cookie(new Cookie(COOKIE, tokenDeAdmin(etapa))))
					.andExpect(status().isUnauthorized());
		}
	}

	@Test
	void unTokenPendienteVencidoDa401() throws Exception {
		Clock pasado = Clock.fixed(Instant.now().minus(Duration.ofMinutes(7)), ZoneOffset.UTC);
		var clave = new SecretKeySpec(propiedades.jwt().secretoBytes(), "HmacSHA256");
		String vencido = new ServicioTokens(new NimbusJwtEncoder(new ImmutableSecret<>(clave)), propiedades, pasado)
				.emitirMfaPendiente(UUID.randomUUID(), UUID.randomUUID());
		mvc.perform(get("/api/auth/me").cookie(new Cookie(COOKIE, vencido)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
	}

	// ---------- Contrasenas ----------

	@Test
	void passwordEncoderUsaPrefijoBcrypt() {
		String hash = passwordEncoder.encode("una-clave-larga-123");
		assertThat(hash).startsWith("{bcrypt}");
		assertThat(passwordEncoder.matches("una-clave-larga-123", hash)).isTrue();
	}

	private static String b64(String s) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}
}
