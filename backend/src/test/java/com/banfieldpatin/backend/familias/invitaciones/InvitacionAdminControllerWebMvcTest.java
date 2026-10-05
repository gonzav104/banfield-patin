package com.banfieldpatin.backend.familias.invitaciones;

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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
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
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.dto.FamiliaResumen;
import com.banfieldpatin.backend.familias.invitaciones.dto.CrearInvitacionSolicitud;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionCreadaRespuesta;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionRespuesta;
import com.banfieldpatin.backend.seguridad.CookieBearerTokenResolver;
import com.banfieldpatin.backend.seguridad.CookieSesion;
import com.banfieldpatin.backend.seguridad.JwtConfig;
import com.banfieldpatin.backend.seguridad.ManejadorAccesoDenegadoJson;
import com.banfieldpatin.backend.seguridad.PuntoEntradaJson;
import com.banfieldpatin.backend.seguridad.SeguridadConfig;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.SesionVigenteDePrueba;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

@WebMvcTest(controllers = InvitacionAdminController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class InvitacionAdminControllerWebMvcTest {

	private static final Instant EXPIRA = Instant.parse("2026-03-08T12:00:00Z");

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	InvitacionAdminService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final UUID invitacionId = UUID.randomUUID();
	private final FamiliaResumen familia = new FamiliaResumen(familiaId, "Familia Prueba");

	private Cookie admin() {
		return new Cookie("BP_SESION", tokens.emitir(adminId, Rol.ADMIN, escuelaId, null));
	}

	private Cookie familiaSesion() {
		return new Cookie("BP_SESION", tokens.emitir(UUID.randomUUID(), Rol.FAMILIA, escuelaId, familiaId));
	}

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder req) throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
		Cookie x = r.getCookie("XSRF-TOKEN");
		return req.cookie(x).header("X-XSRF-TOKEN", x.getValue());
	}

	private MockHttpServletRequestBuilder crear(Cookie sesion, String cuerpo) throws Exception {
		return conCsrf(post("/api/admin/invitaciones").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.cookie(sesion);
	}

	private InvitacionRespuesta respuesta(EstadoInvitacion estado) {
		return new InvitacionRespuesta(invitacionId, familia, "madre@example.com", estado,
				EXPIRA.minusSeconds(3600), EXPIRA, null, null);
	}

	private InvitacionCreadaRespuesta creada() {
		return new InvitacionCreadaRespuesta(invitacionId, EstadoInvitacion.PENDIENTE, "tokenEnClaroDeLaRespuesta",
				"https://app.example.org/registro/invitacion/tokenEnClaroDeLaRespuesta", EXPIRA, familia,
				"madre@example.com");
	}

	// ---------- crear ----------

	@Test
	void adminCreaYRecibe201ConLocationTokenYSinCache() throws Exception {
		when(servicio.crear(any(), any(), any())).thenReturn(creada());

		mvc.perform(crear(admin(), "{\"familiaId\":\"" + familiaId + "\",\"emailSugerido\":\"madre@example.com\"}"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/admin/invitaciones/" + invitacionId))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.token").value("tokenEnClaroDeLaRespuesta"))
				.andExpect(jsonPath("$.estado").value("PENDIENTE"))
				.andExpect(jsonPath("$.familia.id").value(familiaId.toString()))
				.andExpect(jsonPath("$.tokenHash").doesNotExist());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		ArgumentCaptor<CrearInvitacionSolicitud> cuerpo = ArgumentCaptor.forClass(CrearInvitacionSolicitud.class);
		verify(servicio).crear(actor.capture(), cuerpo.capture(), any(DatosSolicitud.class));
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
		assertThat(actor.getValue().id()).isEqualTo(adminId);
		assertThat(cuerpo.getValue().familiaId()).isEqualTo(familiaId);
	}

	@Test
	void conNuevaFamiliaPasaElNombreAlServicio() throws Exception {
		when(servicio.crear(any(), any(), any())).thenReturn(creada());

		mvc.perform(crear(admin(), "{\"nuevaFamilia\":{\"nombreReferencia\":\"Los Gomez\"},\"diasVigencia\":3}"))
				.andExpect(status().isCreated());

		ArgumentCaptor<CrearInvitacionSolicitud> cuerpo = ArgumentCaptor.forClass(CrearInvitacionSolicitud.class);
		verify(servicio).crear(any(), cuerpo.capture(), any());
		assertThat(cuerpo.getValue().nuevaFamilia().nombreReferencia()).isEqualTo("Los Gomez");
		assertThat(cuerpo.getValue().diasVigencia()).isEqualTo(3);
	}

	@Test
	void familiaRecibe403SinTocarElServicio() throws Exception {
		mvc.perform(crear(familiaSesion(), "{\"familiaId\":\"" + familiaId + "\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		verifyNoInteractions(servicio);
	}

	@Test
	void anonimoRecibe401() throws Exception {
		mvc.perform(conCsrf(post("/api/admin/invitaciones").contentType(MediaType.APPLICATION_JSON).content("{}")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		verifyNoInteractions(servicio);
	}

	@Test
	void sinCsrfDa403YElServicioNoSeLlama() throws Exception {
		mvc.perform(post("/api/admin/invitaciones").cookie(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"familiaId\":\"" + familiaId + "\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		verifyNoInteractions(servicio);
	}

	@Test
	void familiaIdYNuevaFamiliaJuntosDan400ConDetalles() throws Exception {
		mvc.perform(crear(admin(), "{\"familiaId\":\"" + familiaId
				+ "\",\"nuevaFamilia\":{\"nombreReferencia\":\"X\"}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"))
				.andExpect(jsonPath("$.detalles[0].campo").value("familiaId"));
		verifyNoInteractions(servicio);
	}

	@Test
	void ningunaFamiliaDa400() throws Exception {
		mvc.perform(crear(admin(), "{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"));
		verifyNoInteractions(servicio);
	}

	@Test
	void deportistaIdEnElCuerpoDa400SolicitudInvalidaYNoLlegaAlServicio() throws Exception {
		MvcResult r = mvc.perform(crear(admin(),
				"{\"familiaId\":\"" + familiaId + "\",\"deportistaId\":\"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
				.andReturn();
		assertThat(r.getResponse().getContentAsString()).doesNotContain("deportistaId");
		verifyNoInteractions(servicio);
	}

	@Test
	void cualquierPropiedadDesconocidaTambienSeRechaza() throws Exception {
		mvc.perform(crear(admin(), "{\"familiaId\":\"" + familiaId + "\",\"escuelaId\":\"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		verifyNoInteractions(servicio);
	}

	@Test
	void emailInvalidoNombreVacioYVigenciaNoPositivaDan400() throws Exception {
		mvc.perform(crear(admin(), "{\"familiaId\":\"" + familiaId + "\",\"emailSugerido\":\"no-es-email\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detalles[0].campo").value("emailSugerido"));
		mvc.perform(crear(admin(), "{\"nuevaFamilia\":{\"nombreReferencia\":\"  \"}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detalles[0].campo").value("nuevaFamilia.nombreReferencia"));
		mvc.perform(crear(admin(), "{\"familiaId\":\"" + familiaId + "\",\"diasVigencia\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detalles[0].campo").value("diasVigencia"));
		verifyNoInteractions(servicio);
	}

	@Test
	void jsonMalformadoDa400() throws Exception {
		mvc.perform(crear(admin(), "{no es json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
	}

	// ---------- listar / obtener ----------

	@Test
	void listaPaginadaNuncaContieneTokenNiHash() throws Exception {
		when(servicio.listar(any(), eq(EstadoInvitacion.PENDIENTE), any()))
				.thenReturn(new Pagina<>(List.of(respuesta(EstadoInvitacion.PENDIENTE)), 0, 20, 1));

		MvcResult r = mvc.perform(get("/api/admin/invitaciones?estado=PENDIENTE").cookie(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.contenido[0].estado").value("PENDIENTE"))
				.andExpect(jsonPath("$.contenido[0].familia.id").value(familiaId.toString()))
				.andExpect(jsonPath("$.totalElementos").value(1))
				.andReturn();
		assertThat(r.getResponse().getContentAsString().toLowerCase()).doesNotContain("token").doesNotContain("hash");
	}

	@Test
	void estadoInvalidoDa400() throws Exception {
		mvc.perform(get("/api/admin/invitaciones?estado=INEXISTENTE").cookie(admin()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		verifyNoInteractions(servicio);
	}

	@Test
	void elTamanioSeAcotaA100() throws Exception {
		when(servicio.listar(any(), any(), any())).thenReturn(new Pagina<>(List.of(), 0, 100, 0));

		mvc.perform(get("/api/admin/invitaciones?size=5000&page=2").cookie(admin())).andExpect(status().isOk());

		ArgumentCaptor<Pageable> p = ArgumentCaptor.forClass(Pageable.class);
		verify(servicio).listar(any(), eq(null), p.capture());
		assertThat(p.getValue().getPageSize()).isEqualTo(100);
		assertThat(p.getValue().getPageNumber()).isEqualTo(2);
	}

	@Test
	void listarYObtenerExigenAdmin() throws Exception {
		mvc.perform(get("/api/admin/invitaciones").cookie(familiaSesion())).andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/invitaciones/" + invitacionId).cookie(familiaSesion()))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/invitaciones")).andExpect(status().isUnauthorized());
		verifyNoInteractions(servicio);
	}

	@Test
	void obtenerDevuelveLaInvitacionDeLaEscuelaDelToken() throws Exception {
		when(servicio.obtener(any(), eq(invitacionId))).thenReturn(respuesta(EstadoInvitacion.PENDIENTE));

		mvc.perform(get("/api/admin/invitaciones/" + invitacionId).cookie(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(invitacionId.toString()));
	}

	// ---------- revocar ----------

	@Test
	void adminRevocaYRecibe200() throws Exception {
		when(servicio.revocar(any(), eq(invitacionId), any())).thenReturn(respuesta(EstadoInvitacion.REVOCADA));

		mvc.perform(conCsrf(post("/api/admin/invitaciones/" + invitacionId + "/revocar")).cookie(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("REVOCADA"));
	}

	@Test
	void revocarSinCsrfDa403YFamiliaDa403YAnonimoDa401() throws Exception {
		String ruta = "/api/admin/invitaciones/" + invitacionId + "/revocar";
		mvc.perform(post(ruta).cookie(admin()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(conCsrf(post(ruta)).cookie(familiaSesion()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		mvc.perform(conCsrf(post(ruta))).andExpect(status().isUnauthorized());
		verifyNoInteractions(servicio);
	}

	@Test
	void erroresDeNegocioUsanElFormatoUniforme() throws Exception {
		when(servicio.revocar(any(), any(), any())).thenThrow(InvitacionAdminService.invitacionNoRevocable());

		mvc.perform(conCsrf(post("/api/admin/invitaciones/" + invitacionId + "/revocar")).cookie(admin()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("INVITACION_NO_REVOCABLE"))
				.andExpect(jsonPath("$.mensaje").isNotEmpty())
				.andExpect(jsonPath("$.detalles").isArray());

		doThrow(InvitacionAdminService.invitacionNoEncontrada()).when(servicio).revocar(any(), any(), any());
		mvc.perform(conCsrf(post("/api/admin/invitaciones/" + invitacionId + "/revocar")).cookie(admin()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("INVITACION_NO_ENCONTRADA"));
	}

	@Test
	void idQueNoEsUuidDa400() throws Exception {
		mvc.perform(get("/api/admin/invitaciones/no-es-uuid").cookie(admin()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
	}
}
