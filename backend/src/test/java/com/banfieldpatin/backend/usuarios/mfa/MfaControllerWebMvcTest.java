package com.banfieldpatin.backend.usuarios.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
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
import com.banfieldpatin.backend.seguridad.SeguridadPropiedades;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;
import com.banfieldpatin.backend.usuarios.mfa.dto.EnrolamientoMfaRespuesta;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import jakarta.servlet.http.Cookie;

/** Matriz de acceso y contrato HTTP de /api/auth/admin/mfa/** con MfaService simulado. */
@WebMvcTest(controllers = MfaController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class })
@ActiveProfiles("test")
class MfaControllerWebMvcTest {

	private static final String CODIGO = "123456";

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	JwtDecoder decoder;
	@Autowired
	SeguridadPropiedades propiedades;
	@MockitoBean
	MfaService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UsuarioActualRespuesta admin = new UsuarioActualRespuesta(adminId, "Root", "Admin",
			"admin@example.com", Rol.ADMIN, escuelaId, null, false, true);

	private Cookie pendiente() {
		return new Cookie("BP_SESION", tokens.emitirMfaPendiente(adminId, escuelaId));
	}

	private Cookie completa() {
		return new Cookie("BP_SESION", tokens.emitir(adminId, Rol.ADMIN, escuelaId, null));
	}

	private Cookie familia() {
		return new Cookie("BP_SESION", tokens.emitir(UUID.randomUUID(), Rol.FAMILIA, escuelaId, UUID.randomUUID()));
	}

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder req) throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
		Cookie x = r.getCookie("XSRF-TOKEN");
		return req.cookie(x).header("X-XSRF-TOKEN", x.getValue());
	}

	private MockHttpServletRequestBuilder codigo(String ruta, Cookie sesion, String cuerpo) throws Exception {
		MockHttpServletRequestBuilder req = conCsrf(post(ruta).contentType(MediaType.APPLICATION_JSON).content(cuerpo));
		return sesion == null ? req : req.cookie(sesion);
	}

	private static String cuerpo(String codigo) {
		return "{\"codigo\":\"" + codigo + "\"}";
	}

	private static String valorDeCookie(MvcResult r) {
		return r.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream().filter(c -> c.startsWith("BP_SESION="))
				.map(c -> c.split(";")[0].substring("BP_SESION=".length())).findFirst().orElseThrow();
	}

	// ---------- enrolar ----------

	@Test
	void enrolarDevuelveUriYSecretoSinCacheYConLaIdentidadDelToken() throws Exception {
		when(servicio.enrolar(any(), any())).thenReturn(new EnrolamientoMfaRespuesta(
				"otpauth://totp/Banfield%20Patin:admin%40example.com?secret=ABC", "ABC"));

		mvc.perform(conCsrf(post("/api/auth/admin/mfa/enrolar")).cookie(pendiente()))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(jsonPath("$.otpauthUri").value("otpauth://totp/Banfield%20Patin:admin%40example.com?secret=ABC"))
				.andExpect(jsonPath("$.secretoBase32").value("ABC"));

		ArgumentCaptor<UsuarioAutenticado> id = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).enrolar(id.capture(), any(DatosSolicitud.class));
		assertThat(id.getValue().id()).isEqualTo(adminId);
		assertThat(id.getValue().escuelaId()).isEqualTo(escuelaId);
	}

	private static final EnrolamientoMfaRespuesta ENROLAMIENTO = new EnrolamientoMfaRespuesta(
			"otpauth://totp/Banfield%20Patin:admin%40example.com?secret=ABC", "ABC");

	private Cookie renovada() {
		return new Cookie("BP_SESION", tokens.emitirMfaPendienteRenovado(adminId, escuelaId));
	}

	private static List<String> sesionesEmitidas(MvcResult r) {
		return r.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream().filter(c -> c.startsWith("BP_SESION="))
				.toList();
	}

	@Test
	void enrolarExitosoRenuevaLaCookiePendienteConVencimientoDesdeAhora() throws Exception {
		when(servicio.enrolar(any(), any())).thenReturn(ENROLAMIENTO);
		Instant antes = Instant.now();

		MvcResult r = mvc.perform(conCsrf(post("/api/auth/admin/mfa/enrolar")).cookie(pendiente()))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
				.andReturn();

		List<String> cookies = sesionesEmitidas(r);
		assertThat(cookies).hasSize(1);
		assertThat(cookies.get(0)).contains("HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=300");
		var jwt = decoder.decode(valorDeCookie(r));
		assertThat(jwt.getClaimAsString("mfa")).isEqualTo("PENDIENTE");
		assertThat(jwt.getClaimAsString("rol")).isEqualTo("ADMIN");
		assertThat(jwt.getSubject()).isEqualTo(adminId.toString());
		assertThat(jwt.getClaimAsString("escuela_id")).isEqualTo(escuelaId.toString());
		assertThat((Boolean) jwt.getClaim("mfa_renovado")).isTrue();
		// El vencimiento cuenta desde la emision de la renovacion (no desde el login): exp = iat + duracion-pendiente.
		assertThat(jwt.getIssuedAt()).isBetween(antes.minusSeconds(2), Instant.now().plusSeconds(2));
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
		// El secreto va en el cuerpo y el token renovado nunca lo contiene.
		assertThat(valorDeCookie(r)).doesNotContain("ABC");
	}

	@Test
	void enrolarConUnTokenYaRenovadoReEnrolaPeroNoVuelveARenovar() throws Exception {
		when(servicio.enrolar(any(), any())).thenReturn(ENROLAMIENTO);

		MvcResult r = mvc.perform(conCsrf(post("/api/auth/admin/mfa/enrolar")).cookie(renovada()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.secretoBase32").value("ABC"))
				.andReturn();

		verify(servicio).enrolar(any(), any());
		assertThat(sesionesEmitidas(r)).isEmpty();
	}

	@Test
	void enrolarFallidoNuncaEmiteCookie() throws Exception {
		// 409: MFA ya confirmado.
		when(servicio.enrolar(any(), any())).thenThrow(MfaService.estadoInvalido());
		MvcResult conflicto = mvc.perform(conCsrf(post("/api/auth/admin/mfa/enrolar")).cookie(pendiente()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("MFA_ESTADO_INVALIDO"))
				.andReturn();
		assertThat(sesionesEmitidas(conflicto)).isEmpty();

		// Falla la auditoria/DB (la transaccion del servicio revierte): 500 sin cookie renovada.
		doThrow(new IllegalStateException("fallo de base de datos")).when(servicio).enrolar(any(), any());
		MvcResult interno = mvc.perform(conCsrf(post("/api/auth/admin/mfa/enrolar")).cookie(pendiente()))
				.andExpect(status().isInternalServerError())
				.andReturn();
		assertThat(sesionesEmitidas(interno)).isEmpty();
	}

	@Test
	void enrolarSinSesionPendienteNoEmiteCookie() throws Exception {
		MvcResult anonimo = mvc.perform(conCsrf(post("/api/auth/admin/mfa/enrolar"))).andExpect(status().isUnauthorized())
				.andReturn();
		assertThat(sesionesEmitidas(anonimo)).isEmpty();
		for (Cookie sesion : new Cookie[] { completa(), familia() }) {
			MvcResult r = mvc.perform(conCsrf(post("/api/auth/admin/mfa/enrolar")).cookie(sesion))
					.andExpect(status().isForbidden()).andReturn();
			assertThat(sesionesEmitidas(r)).isEmpty();
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void confirmarConElTokenRenovadoEmiteLaSesionCompletaSinMarcaDeRenovacion() throws Exception {
		when(servicio.confirmar(any(), eq(CODIGO), any())).thenReturn(admin);

		MvcResult r = mvc.perform(codigo("/api/auth/admin/mfa/confirmar", renovada(), cuerpo(CODIGO)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mfaPendiente").value(false))
				.andReturn();

		var jwt = decoder.decode(valorDeCookie(r));
		assertThat(jwt.getClaimAsString("mfa")).isEqualTo("COMPLETADA");
		assertThat(jwt.getClaimAsString("rol")).isEqualTo("ADMIN");
		assertThat((Object) jwt.getClaim("mfa_renovado")).isNull();
		assertThat(sesionesEmitidas(r).get(0)).contains("Max-Age=28800");
	}

	// ---------- confirmar / verificar ----------

	@Test
	void confirmarConCodigoValidoEmiteLaSesionCompletaYRotaElCsrf() throws Exception {
		when(servicio.confirmar(any(), eq(CODIGO), any())).thenReturn(admin);

		MvcResult r = mvc.perform(codigo("/api/auth/admin/mfa/confirmar", pendiente(), cuerpo(CODIGO)))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(jsonPath("$.mfaPendiente").value(false))
				.andExpect(jsonPath("$.mfaEnrolado").value(true))
				.andExpect(jsonPath("$.token").doesNotExist())
				.andReturn();

		String setCookie = r.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
				.filter(c -> c.startsWith("BP_SESION=")).findFirst().orElseThrow();
		assertThat(setCookie).contains("HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=28800");
		var jwt = decoder.decode(valorDeCookie(r));
		assertThat(jwt.getClaimAsString("mfa")).isEqualTo("COMPLETADA");
		assertThat(jwt.getClaimAsString("rol")).isEqualTo("ADMIN");
		assertThat(jwt.getSubject()).isEqualTo(adminId.toString());
		assertThat(r.getResponse().getContentAsString()).doesNotContain(valorDeCookie(r));
		// Rota el CSRF: la cookie XSRF anterior se expira.
		Cookie xsrf = r.getResponse().getCookie("XSRF-TOKEN");
		assertThat(xsrf).isNotNull();
		assertThat(xsrf.getMaxAge()).isZero();
		assertThat(xsrf.getValue()).isEmpty();
	}

	@Test
	void verificarConCodigoValidoEmiteLaSesionCompleta() throws Exception {
		when(servicio.verificar(any(), eq(CODIGO), any())).thenReturn(admin);

		MvcResult r = mvc.perform(codigo("/api/auth/admin/mfa/verificar", pendiente(), cuerpo(CODIGO)))
				.andExpect(status().isOk())
				.andReturn();

		assertThat(decoder.decode(valorDeCookie(r)).getClaimAsString("mfa")).isEqualTo("COMPLETADA");
	}

	@Test
	void unCodigoIncorrectoDa401UniformeYNoEmiteCookieDeSesion() throws Exception {
		when(servicio.verificar(any(), any(), any())).thenThrow(MfaService.codigoInvalido());

		MvcResult r = mvc.perform(codigo("/api/auth/admin/mfa/verificar", pendiente(), cuerpo(CODIGO)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("CODIGO_MFA_INVALIDO"))
				.andReturn();

		assertThat(r.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(c -> c.startsWith("BP_SESION="));
		assertThat(r.getResponse().getContentAsString()).doesNotContain(CODIGO);
	}

	@Test
	void elEstadoIncorrectoDa409() throws Exception {
		when(servicio.confirmar(any(), any(), any())).thenThrow(MfaService.estadoInvalido());

		mvc.perform(codigo("/api/auth/admin/mfa/confirmar", pendiente(), cuerpo(CODIGO)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("MFA_ESTADO_INVALIDO"));
	}

	@Test
	void unCodigoQueNoSonExactamenteSeisDigitosDa400SinLlamarAlServicio() throws Exception {
		String[] malos = { "12345", "1234567", "12345a", "abcdef", "12 456", " 12345", "123456 ", "١٢٣٤٥٦", "12.456",
				"-12345", "", "  " };
		for (String malo : malos) {
			for (String ruta : new String[] { "/api/auth/admin/mfa/confirmar", "/api/auth/admin/mfa/verificar" }) {
				mvc.perform(codigo(ruta, pendiente(), cuerpo(malo)))
						.andExpect(status().isBadRequest())
						.andExpect(jsonPath("$.codigo").value("VALIDACION"));
			}
		}
		// Falta el campo, nulo, cuerpo vacio y cuerpo que no es JSON.
		for (String raro : new String[] { "{}", "{\"codigo\":null}", "{\"codigo\":[\"123456\"]}", "no-json", "" }) {
			mvc.perform(codigo("/api/auth/admin/mfa/verificar", pendiente(), raro))
					.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void camposDesconocidosSeIgnoranYNoAfectanLaIdentidad() throws Exception {
		when(servicio.verificar(any(), eq(CODIGO), any())).thenReturn(admin);

		mvc.perform(codigo("/api/auth/admin/mfa/verificar", pendiente(),
				"{\"codigo\":\"123456\",\"usuarioId\":\"" + UUID.randomUUID() + "\",\"rol\":\"FAMILIA\"}"))
				.andExpect(status().isOk());

		ArgumentCaptor<UsuarioAutenticado> id = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).verificar(id.capture(), eq(CODIGO), any());
		assertThat(id.getValue().id()).isEqualTo(adminId);
	}

	@Test
	void laSolicitudNoImprimeElCodigo() {
		assertThat(new com.banfieldpatin.backend.usuarios.mfa.dto.CodigoMfaSolicitud(CODIGO).toString())
				.doesNotContain(CODIGO);
	}

	// ---------- matriz de acceso ----------

	@Test
	void sinCsrfDa403YElServicioNoSeLlama() throws Exception {
		for (String ruta : new String[] { "enrolar", "confirmar", "verificar" }) {
			mvc.perform(post("/api/auth/admin/mfa/" + ruta).cookie(pendiente()).contentType(MediaType.APPLICATION_JSON)
					.content(cuerpo(CODIGO)))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void anonimoDa401() throws Exception {
		for (String ruta : new String[] { "enrolar", "confirmar", "verificar" }) {
			mvc.perform(codigo("/api/auth/admin/mfa/" + ruta, null, cuerpo(CODIGO)))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void adminConSesionCompletaYFamiliaDan403() throws Exception {
		for (Cookie sesion : new Cookie[] { completa(), familia() }) {
			for (String ruta : new String[] { "enrolar", "confirmar", "verificar" }) {
				mvc.perform(codigo("/api/auth/admin/mfa/" + ruta, sesion, cuerpo(CODIGO)))
						.andExpect(status().isForbidden())
						.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
			}
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void unTokenPendienteVencidoDa401() throws Exception {
		Clock pasado = Clock.fixed(Instant.now().minus(Duration.ofMinutes(7)), ZoneOffset.UTC);
		var clave = new SecretKeySpec(propiedades.jwt().secretoBytes(), "HmacSHA256");
		String vencido = new ServicioTokens(new NimbusJwtEncoder(new ImmutableSecret<>(clave)), propiedades, pasado)
				.emitirMfaPendiente(adminId, escuelaId);

		mvc.perform(codigo("/api/auth/admin/mfa/verificar", new Cookie("BP_SESION", vencido), cuerpo(CODIGO)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		verifyNoInteractions(servicio);
	}

	@Test
	void soloSePermitePost() throws Exception {
		mvc.perform(get("/api/auth/admin/mfa/enrolar").cookie(pendiente())).andExpect(status().is4xxClientError());
		verifyNoInteractions(servicio);
	}
}
