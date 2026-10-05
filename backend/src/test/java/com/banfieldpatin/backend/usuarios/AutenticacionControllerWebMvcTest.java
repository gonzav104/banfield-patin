package com.banfieldpatin.backend.usuarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.seguridad.CookieBearerTokenResolver;
import com.banfieldpatin.backend.seguridad.CookieSesion;
import com.banfieldpatin.backend.seguridad.JwtConfig;
import com.banfieldpatin.backend.seguridad.ManejadorAccesoDenegadoJson;
import com.banfieldpatin.backend.seguridad.PuntoEntradaJson;
import com.banfieldpatin.backend.seguridad.SeguridadConfig;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.SesionVigenteDePrueba;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;

import jakarta.servlet.http.Cookie;

@WebMvcTest(controllers = AutenticacionController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class AutenticacionControllerWebMvcTest {

	private static final String CUERPO = "{\"email\":\"ana@example.com\",\"password\":\"clave-correcta-123\"}";

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	JwtDecoder decoder;
	@MockitoBean
	AutenticacionService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final UsuarioActualRespuesta familia = new UsuarioActualRespuesta(UUID.randomUUID(), "Ana", "Perez",
			"ana@example.com", Rol.FAMILIA, escuelaId, familiaId);

	private Cookie xsrf() throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
		return r.getCookie("XSRF-TOKEN");
	}

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder req, Cookie... cookies)
			throws Exception {
		Cookie x = xsrf();
		req.cookie(x).header("X-XSRF-TOKEN", x.getValue());
		for (Cookie c : cookies) {
			req.cookie(c);
		}
		return req;
	}

	private MockHttpServletRequestBuilder login(String ruta, String cuerpo) throws Exception {
		return conCsrf(post(ruta).contentType(MediaType.APPLICATION_JSON).content(cuerpo));
	}

	private Cookie sesion() {
		return new Cookie("BP_SESION", tokens.emitir(familia.id(), Rol.FAMILIA, escuelaId, familiaId));
	}

	// ---------- login ----------

	@Test
	void loginExitosoSeteaCookieHttpOnlyLaxSinTokenNiHashEnElCuerpo() throws Exception {
		when(servicio.autenticar(eq(Rol.FAMILIA), eq("ana@example.com"), any(), any(DatosSolicitud.class)))
				.thenReturn(familia);

		MvcResult r = mvc.perform(login("/api/auth/login", CUERPO))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(familia.id().toString()))
				.andExpect(jsonPath("$.rol").value("FAMILIA"))
				.andExpect(jsonPath("$.escuelaId").value(escuelaId.toString()))
				.andExpect(jsonPath("$.familiaId").value(familiaId.toString()))
				.andExpect(jsonPath("$.token").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andReturn();

		String setCookie = r.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
				.filter(c -> c.startsWith("BP_SESION=")).findFirst().orElseThrow();
		assertThat(setCookie).contains("HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=28800")
				.doesNotContain("Secure");
		assertThat(r.getResponse().getContentAsString()).doesNotContain(setCookie.split(";")[0].substring(10));
	}

	@Test
	void adminLoginUsaRolAdmin() throws Exception {
		UsuarioActualRespuesta admin = new UsuarioActualRespuesta(UUID.randomUUID(), "Root", "Admin",
				"admin@example.com", Rol.ADMIN, escuelaId, null);
		when(servicio.autenticar(eq(Rol.ADMIN), eq("admin@example.com"), any(), any())).thenReturn(admin);

		mvc.perform(login("/api/auth/admin/login",
				"{\"email\":\"admin@example.com\",\"password\":\"clave-correcta-123\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rol").value("ADMIN"))
				.andExpect(header().exists(HttpHeaders.SET_COOKIE));
	}

	private static String valorDeCookie(MvcResult r) {
		return r.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream().filter(c -> c.startsWith("BP_SESION="))
				.map(c -> c.split(";")[0].substring("BP_SESION=".length())).findFirst().orElseThrow();
	}

	@Test
	void adminLoginNoEmiteSesionCompletaSinoUnTokenConMfaPendienteDeVidaCorta() throws Exception {
		UsuarioActualRespuesta admin = new UsuarioActualRespuesta(UUID.randomUUID(), "Root", "Admin",
				"admin@example.com", Rol.ADMIN, escuelaId, null);
		when(servicio.autenticar(eq(Rol.ADMIN), eq("admin@example.com"), any(), any())).thenReturn(admin);

		MvcResult r = mvc.perform(login("/api/auth/admin/login",
				"{\"email\":\"admin@example.com\",\"password\":\"clave-correcta-123\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mfaPendiente").value(true))
				.andExpect(jsonPath("$.token").doesNotExist())
				.andReturn();

		String setCookie = r.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
				.filter(c -> c.startsWith("BP_SESION=")).findFirst().orElseThrow();
		assertThat(setCookie).contains("HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=300")
				.doesNotContain("Max-Age=28800");
		var jwt = decoder.decode(valorDeCookie(r));
		assertThat(jwt.getClaimAsString("mfa")).isEqualTo("PENDIENTE");
		assertThat(jwt.getClaimAsString("rol")).isEqualTo("ADMIN");
		assertThat(jwt.getSubject()).isEqualTo(admin.id().toString());
	}

	@Test
	void loginDeFamiliaNoSeVeAfectadoPorMfa() throws Exception {
		when(servicio.autenticar(eq(Rol.FAMILIA), eq("ana@example.com"), any(), any())).thenReturn(familia);

		MvcResult r = mvc.perform(login("/api/auth/login", CUERPO))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mfaPendiente").value(false))
				.andReturn();

		assertThat(decoder.decode(valorDeCookie(r)).getClaims()).doesNotContainKey("mfa");
	}

	@Test
	void credencialesInvalidasDa401SinCookieDeSesion() throws Exception {
		when(servicio.autenticar(any(), any(), any(), any())).thenThrow(AutenticacionService.credencialesInvalidas());

		MvcResult r = mvc.perform(login("/api/auth/login", CUERPO))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"))
				.andReturn();
		assertThat(r.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(c -> c.startsWith("BP_SESION="));
	}

	@Test
	void loginYAdminLoginRechazadosPorRolDanElMismoCuerpoExacto() throws Exception {
		when(servicio.autenticar(any(), any(), any(), any())).thenThrow(AutenticacionService.credencialesInvalidas());

		String deLogin = mvc.perform(login("/api/auth/login", CUERPO)).andReturn().getResponse().getContentAsString();
		String deAdmin = mvc.perform(login("/api/auth/admin/login", CUERPO)).andReturn().getResponse()
				.getContentAsString();

		assertThat(deAdmin).isEqualTo(deLogin).contains("CREDENCIALES_INVALIDAS");
	}

	@Test
	void bloqueoEsIndistinguibleDeCredencialesInvalidas() throws Exception {
		// El servicio lanza la misma excepcion en ambos casos (R3): sin 429 ni Retry-After.
		when(servicio.autenticar(any(), any(), eq("clave-mala"), any()))
				.thenThrow(AutenticacionService.credencialesInvalidas());
		when(servicio.autenticar(any(), any(), eq("clave-correcta-123"), any()))
				.thenThrow(AutenticacionService.credencialesInvalidas());

		MvcResult mala = mvc.perform(login("/api/auth/login", "{\"email\":\"a@x.com\",\"password\":\"clave-mala\"}"))
				.andReturn();
		MvcResult bloqueada = mvc.perform(login("/api/auth/login", CUERPO)).andReturn();

		assertThat(bloqueada.getResponse().getStatus()).isEqualTo(mala.getResponse().getStatus()).isEqualTo(401);
		assertThat(bloqueada.getResponse().getContentAsString()).isEqualTo(mala.getResponse().getContentAsString());
		assertThat(bloqueada.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNull();
	}

	@Test
	void camposVaciosDan400ConDetallesYNoLlamanAlServicio() throws Exception {
		mvc.perform(login("/api/auth/login", "{\"email\":\"\",\"password\":\" \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"))
				.andExpect(jsonPath("$.detalles.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
		verifyNoInteractions(servicio);
	}

	@Test
	void escuelaIdYRolEnElCuerpoSeIgnoran() throws Exception {
		when(servicio.autenticar(eq(Rol.FAMILIA), eq("ana@example.com"), any(), any())).thenReturn(familia);

		mvc.perform(login("/api/auth/login", "{\"email\":\"ana@example.com\",\"password\":\"clave-correcta-123\","
				+ "\"escuelaId\":\"" + UUID.randomUUID() + "\",\"rol\":\"ADMIN\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rol").value("FAMILIA"));
		verify(servicio).autenticar(eq(Rol.FAMILIA), eq("ana@example.com"), any(), any());
	}

	@Test
	void sinCsrfDa403YElServicioNoSeLlama() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(CUERPO))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		verifyNoInteractions(servicio);
	}

	@Test
	void toStringDeLaSolicitudNoExponeLaContrasena() {
		assertThat(new com.banfieldpatin.backend.usuarios.dto.LoginSolicitud("a@x.com", "secreta-123").toString())
				.doesNotContain("secreta-123");
	}

	// ---------- logout ----------

	@Test
	void logoutConSesionYCsrfDa204ExpiraLaCookieYAudita() throws Exception {
		MvcResult r = mvc.perform(conCsrf(post("/api/auth/logout"), sesion()))
				.andExpect(status().isNoContent())
				.andReturn();

		assertThat(r.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.anyMatch(c -> c.startsWith("BP_SESION=;") && c.contains("Max-Age=0") && c.contains("HttpOnly"));
		verify(servicio).cerrarSesion(any(), any());
	}

	@Test
	void logoutSinSesionDa401YSinCsrfDa403() throws Exception {
		mvc.perform(conCsrf(post("/api/auth/logout"))).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/logout").cookie(sesion()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		verify(servicio, never()).cerrarSesion(any(), any());
	}

	// ---------- me ----------

	@Test
	void meExponeLaEtapaMfaDeUnAdminConTokenPendienteYLaOcultaConSesionCompleta() throws Exception {
		UUID adminId = UUID.randomUUID();
		UsuarioActualRespuesta admin = new UsuarioActualRespuesta(adminId, "Root", "Admin", "admin@example.com",
				Rol.ADMIN, escuelaId, null, false, true);
		when(servicio.actual(any())).thenReturn(Optional.of(admin));

		Cookie pendiente = new Cookie("BP_SESION", tokens.emitirMfaPendiente(adminId, escuelaId));
		mvc.perform(get("/api/auth/me").cookie(pendiente))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mfaPendiente").value(true))
				.andExpect(jsonPath("$.mfaEnrolado").value(true));

		Cookie completa = new Cookie("BP_SESION", tokens.emitir(adminId, Rol.ADMIN, escuelaId, null));
		mvc.perform(get("/api/auth/me").cookie(completa))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mfaPendiente").value(false));

		when(servicio.actual(any())).thenReturn(Optional.of(familia));
		mvc.perform(get("/api/auth/me").cookie(sesion()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mfaPendiente").value(false))
				.andExpect(jsonPath("$.mfaEnrolado").value(false));
	}

	@Test
	void meDevuelveLaIdentidadSinDatosInternos() throws Exception {
		when(servicio.actual(any())).thenReturn(Optional.of(familia));

		mvc.perform(get("/api/auth/me").cookie(sesion()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("ana@example.com"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.activo").doesNotExist());
	}

	@Test
	void meSinCookieDa401() throws Exception {
		mvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
	}

	@Test
	void meDeUsuarioDesactivadoDa401YLimpiaLaCookie() throws Exception {
		when(servicio.actual(any())).thenReturn(Optional.empty());

		MvcResult r = mvc.perform(get("/api/auth/me").cookie(sesion()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
				.andReturn();
		assertThat(r.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.anyMatch(c -> c.startsWith("BP_SESION=;") && c.contains("Max-Age=0"));
	}
}
