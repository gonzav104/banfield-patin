package com.banfieldpatin.backend.familias.invitaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionPublicaRespuesta;
import com.banfieldpatin.backend.familias.invitaciones.dto.RegistroSolicitud;
import com.banfieldpatin.backend.seguridad.CookieBearerTokenResolver;
import com.banfieldpatin.backend.seguridad.CookieSesion;
import com.banfieldpatin.backend.seguridad.JwtConfig;
import com.banfieldpatin.backend.seguridad.ManejadorAccesoDenegadoJson;
import com.banfieldpatin.backend.seguridad.PuntoEntradaJson;
import com.banfieldpatin.backend.seguridad.SeguridadConfig;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.SesionVigenteDePrueba;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;

import jakarta.servlet.http.Cookie;

@WebMvcTest(controllers = RegistroController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class RegistroControllerWebMvcTest {

	private static final String TOKEN = "t".repeat(43);
	private static final String PASSWORD = "clave-secreta-123";

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	ValidarInvitacionService validacion;
	@MockitoBean
	RegistroPorInvitacionService registro;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder req) throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
		Cookie x = r.getCookie("XSRF-TOKEN");
		return req.cookie(x).header("X-XSRF-TOKEN", x.getValue());
	}

	private MockHttpServletRequestBuilder registrar(String cuerpo) throws Exception {
		return conCsrf(post("/api/auth/registro/invitacion").contentType(MediaType.APPLICATION_JSON).content(cuerpo));
	}

	private MockHttpServletRequestBuilder validar(String cuerpo) throws Exception {
		return conCsrf(post("/api/auth/invitaciones/validar").contentType(MediaType.APPLICATION_JSON).content(cuerpo));
	}

	private static String cuerpoRegistro(String extra) {
		return "{\"token\":\"" + TOKEN + "\",\"nombre\":\"Ana\",\"apellido\":\"Perez\","
				+ "\"email\":\"ana@example.com\",\"password\":\"" + PASSWORD + "\"" + extra + "}";
	}

	private UsuarioActualRespuesta usuario() {
		return new UsuarioActualRespuesta(UUID.randomUUID(), "Ana", "Perez", "ana@example.com", Rol.FAMILIA,
				escuelaId, familiaId);
	}

	// ---------- registro ----------

	@Test
	void registroDevuelve201ConIdentidadYSinCookieDeSesion() throws Exception {
		when(registro.registrar(any(), any())).thenReturn(usuario());

		MvcResult r = mvc.perform(registrar(cuerpoRegistro("")))
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.rol").value("FAMILIA"))
				.andExpect(jsonPath("$.email").value("ana@example.com"))
				.andExpect(jsonPath("$.token").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andReturn();

		assertThat(r.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.contains("BP_SESION"));
		assertThat(r.getResponse().getContentAsString()).doesNotContain(PASSWORD).doesNotContain(TOKEN);
	}

	@Test
	void rolEscuelaFamiliaYActivoDelClienteSeIgnoran() throws Exception {
		when(registro.registrar(any(), any())).thenReturn(usuario());

		mvc.perform(registrar(cuerpoRegistro(",\"rol\":\"ADMIN\",\"familiaId\":\"" + UUID.randomUUID()
				+ "\",\"escuelaId\":\"" + UUID.randomUUID() + "\",\"activo\":false")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.rol").value("FAMILIA"));

		ArgumentCaptor<RegistroSolicitud> solicitud = ArgumentCaptor.forClass(RegistroSolicitud.class);
		verify(registro).registrar(solicitud.capture(), any(DatosSolicitud.class));
		assertThat(solicitud.getValue().token()).isEqualTo(TOKEN);
		assertThat(solicitud.getValue().email()).isEqualTo("ana@example.com");
		assertThat(solicitud.getValue().password()).isEqualTo(PASSWORD);
	}

	@Test
	void unaCookieDeSesionInvalidaNoImpideRegistrarse() throws Exception {
		when(registro.registrar(any(), any())).thenReturn(usuario());

		mvc.perform(registrar(cuerpoRegistro("")).cookie(new Cookie("BP_SESION", "esto-no-es-un-jwt")))
				.andExpect(status().isCreated());
	}

	@Test
	void registroSinCsrfDa403YNoLlegaAlServicio() throws Exception {
		mvc.perform(post("/api/auth/registro/invitacion").contentType(MediaType.APPLICATION_JSON)
				.content(cuerpoRegistro("")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));

		verifyNoInteractions(registro);
	}

	@Test
	void contrasenaCortaOConMasDe72BytesDan400SinConsumirLaInvitacion() throws Exception {
		String corta = "{\"token\":\"" + TOKEN + "\",\"nombre\":\"Ana\",\"apellido\":\"Perez\","
				+ "\"email\":\"ana@example.com\",\"password\":\"corta\"}";
		MvcResult r = mvc.perform(registrar(corta))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"))
				.andExpect(jsonPath("$.detalles[0].campo").value("password"))
				.andReturn();
		assertThat(r.getResponse().getContentAsString()).doesNotContain("corta\"");

		String larga = "{\"token\":\"" + TOKEN + "\",\"nombre\":\"Ana\",\"apellido\":\"Perez\","
				+ "\"email\":\"ana@example.com\",\"password\":\"" + "x".repeat(73) + "\"}";
		mvc.perform(registrar(larga)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detalles[0].campo").value("password"));

		verifyNoInteractions(registro);
	}

	@Test
	void emailInvalidoNombresVaciosYTokenFaltanteDan400() throws Exception {
		String base = "\"nombre\":\"Ana\",\"apellido\":\"Perez\",\"email\":\"ana@example.com\",\"password\":\""
				+ PASSWORD + "\"";
		mvc.perform(registrar("{\"token\":\"" + TOKEN + "\",\"nombre\":\"Ana\",\"apellido\":\"Perez\","
				+ "\"email\":\"no-es-email\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.detalles[0].campo").value("email"));
		mvc.perform(registrar("{\"token\":\"" + TOKEN + "\",\"nombre\":\"  \",\"apellido\":\"Perez\","
				+ "\"email\":\"ana@example.com\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.detalles[0].campo").value("nombre"));
		mvc.perform(registrar("{" + base + "}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.detalles[0].campo").value("token"));

		verifyNoInteractions(registro);
	}

	@Test
	void emailDuplicadoDa409ConSuCodigo() throws Exception {
		when(registro.registrar(any(), any())).thenThrow(RegistroPorInvitacionService.emailYaRegistrado());

		mvc.perform(registrar(cuerpoRegistro("")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("EMAIL_YA_REGISTRADO"));
	}

	@Test
	void invitacionNoDisponibleEnRegistroYEnValidarTienenElMismoCuerpo() throws Exception {
		when(registro.registrar(any(), any())).thenThrow(ValidarInvitacionService.invitacionNoDisponible());
		when(validacion.validar(any(), any())).thenThrow(ValidarInvitacionService.invitacionNoDisponible());

		String enRegistro = mvc.perform(registrar(cuerpoRegistro("")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("INVITACION_NO_DISPONIBLE"))
				.andReturn().getResponse().getContentAsString();
		String enValidar = mvc.perform(validar("{\"token\":\"" + TOKEN + "\"}"))
				.andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();

		assertThat(enValidar).isEqualTo(enRegistro);
	}

	// ---------- validar ----------

	@Test
	void validarDevuelve200ConContextoMinimoYSinIdsInternos() throws Exception {
		Instant expira = Instant.parse("2026-03-08T12:00:00Z");
		when(validacion.validar(eq(TOKEN), any())).thenReturn(
				new InvitacionPublicaRespuesta(true, "Escuela de prueba", "madre@example.com", expira));

		MvcResult r = mvc.perform(validar("{\"token\":\"" + TOKEN + "\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.valida").value(true))
				.andExpect(jsonPath("$.escuelaNombre").value("Escuela de prueba"))
				.andExpect(jsonPath("$.emailSugerido").value("madre@example.com"))
				.andExpect(jsonPath("$.familiaId").doesNotExist())
				.andExpect(jsonPath("$.id").doesNotExist())
				.andReturn();
		assertThat(r.getResponse().getContentAsString()).doesNotContain(TOKEN);
	}

	@Test
	void validarSinCsrfDa403() throws Exception {
		mvc.perform(post("/api/auth/invitaciones/validar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + TOKEN + "\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));

		verifyNoInteractions(validacion);
	}

	@Test
	void validarConTokenVacioOExcesivoDa400DeValidacion() throws Exception {
		mvc.perform(validar("{\"token\":\"  \"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"));
		mvc.perform(validar("{\"token\":\"" + "x".repeat(101) + "\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"));
		mvc.perform(validar("{}")).andExpect(status().isBadRequest());

		verifyNoInteractions(validacion);
	}

	// ---------- no hay otra via de alta ----------

	@Test
	void noExisteRegistroLibreSinInvitacion() throws Exception {
		mvc.perform(conCsrf(post("/api/auth/registro").contentType(MediaType.APPLICATION_JSON)
				.content(cuerpoRegistro(""))))
				.andExpect(status().isUnauthorized());

		Cookie admin = new Cookie("BP_SESION", tokens.emitir(UUID.randomUUID(), Rol.ADMIN, escuelaId, null));
		int estado = mvc.perform(conCsrf(post("/api/auth/registro").contentType(MediaType.APPLICATION_JSON)
				.content(cuerpoRegistro(""))).cookie(admin)).andReturn().getResponse().getStatus();

		assertThat(estado).isIn(404, 405);
		verifyNoInteractions(registro);
	}
}
