package com.banfieldpatin.backend.familias.tutores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.tutores.dto.TutorRespuesta;
import com.banfieldpatin.backend.familias.tutores.dto.TutorActualizacion;
import com.banfieldpatin.backend.familias.tutores.dto.TutorSolicitud;
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

@WebMvcTest(controllers = TutorAdminController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class TutorAdminControllerWebMvcTest {

	private static final String VALIDO = "{\"nombre\":\"Ana\",\"apellido\":\"Perez\"}";

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	TutorAdminService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final UUID tutorId = UUID.randomUUID();

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

	/**
	 * Los cuerpos de estas pruebas describen los DATOS del tutor: aqui se les agregan las revisiones que el contrato exige
	 * (alta: la de la familia; edicion: la del tutor y la de la familia). Las pruebas de las revisiones usan {@link #crudo}.
	 */
	private MockHttpServletRequestBuilder cuerpo(MockHttpServletRequestBuilder req, Cookie sesion, String json)
			throws Exception {
		String metodo = req.buildRequest(new MockServletContext()).getMethod();
		String completo = switch (metodo) {
			case "POST" -> json.substring(0, json.length() - 1) + ",\"versionFamiliaEsperada\":\"0\"}";
			case "PUT" -> json.substring(0, json.length() - 1) + ",\"versionEsperada\":\"0\",\"versionFamiliaEsperada\":\"0\"}";
			default -> json;
		};
		return crudo(req, sesion, completo);
	}

	private MockHttpServletRequestBuilder crudo(MockHttpServletRequestBuilder req, Cookie sesion, String json)
			throws Exception {
		return conCsrf(req.contentType(MediaType.APPLICATION_JSON).content(json)).cookie(sesion);
	}

	private TutorRespuesta respuesta() {
		return new TutorRespuesta(tutorId, familiaId, "Ana", "Perez", "30111222", "11-5555-0000", "ana@example.com",
				"Madre", true, "0", "VERIFICADO", null, "ADMIN");
	}

	// ---------- crear ----------

	@Test
	void crearDevuelve201ConLocationYLaFamiliaDeLaRutaSinUsuarioNiEscuela() throws Exception {
		when(servicio.crear(any(), eq(familiaId), any(), any())).thenReturn(respuesta());

		mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/tutores"), admin(),
				"{\"nombre\":\"  Ana \",\"apellido\":\"Perez\",\"dni\":\"30.111.222\",\"email\":\"ANA@Example.com\","
						+ "\"telefono\":\"  \",\"parentesco\":\"Madre\"}"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/admin/tutores/" + tutorId))
				.andExpect(jsonPath("$.id").value(tutorId.toString()))
				.andExpect(jsonPath("$.familiaId").value(familiaId.toString()))
				.andExpect(jsonPath("$.activo").value(true))
				.andExpect(jsonPath("$.usuarioId").doesNotExist())
				.andExpect(jsonPath("$.escuelaId").doesNotExist());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		ArgumentCaptor<TutorSolicitud> solicitud = ArgumentCaptor.forClass(TutorSolicitud.class);
		verify(servicio).crear(actor.capture(), eq(familiaId), solicitud.capture(), any(DatosSolicitud.class));
		assertThat(actor.getValue().id()).isEqualTo(adminId);
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
		assertThat(solicitud.getValue().nombre()).isEqualTo("Ana");
		assertThat(solicitud.getValue().email()).isEqualTo("ana@example.com");
		assertThat(solicitud.getValue().telefono()).isNull();
		assertThat(solicitud.getValue().dni()).isEqualTo("30.111.222");
	}

	@Test
	void crearConElMinimoNombreYApellidoEsValido() throws Exception {
		when(servicio.crear(any(), any(), any(), any())).thenReturn(respuesta());

		mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/tutores"), admin(), VALIDO))
				.andExpect(status().isCreated());
	}

	@Test
	void dniInvalidoDa400ValidacionNombrandoElCampo() throws Exception {
		for (String dni : new String[] { "12ab", "123456", "1234567890", "12-345-678" }) {
			mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/tutores"), admin(),
					"{\"nombre\":\"Ana\",\"apellido\":\"Perez\",\"dni\":\"" + dni + "\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("VALIDACION"))
					.andExpect(jsonPath("$.detalles[0].campo").value("dni"))
					.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(dni))));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void emailInvalidoNombreFaltanteOLargosExcedidosDan400Validacion() throws Exception {
		String[][] casos = {
				{ "{\"nombre\":\"Ana\",\"apellido\":\"Perez\",\"email\":\"no-es-email\"}", "email" },
				{ "{\"apellido\":\"Perez\"}", "nombre" },
				{ "{\"nombre\":\"   \",\"apellido\":\"Perez\"}", "nombre" },
				{ "{\"nombre\":\"Ana\"}", "apellido" },
				{ "{\"nombre\":\"" + "a".repeat(101) + "\",\"apellido\":\"Perez\"}", "nombre" },
				{ "{\"nombre\":\"Ana\",\"apellido\":\"Perez\",\"telefono\":\"" + "1".repeat(41) + "\"}", "telefono" },
				{ "{\"nombre\":\"Ana\",\"apellido\":\"Perez\",\"parentesco\":\"" + "a".repeat(41) + "\"}", "parentesco" },
				{ "{\"nombre\":\"Ana\",\"apellido\":\"Perez\",\"email\":\"" + "a".repeat(175) + "@x.com\"}", "email" } };
		for (String[] caso : casos) {
			mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/tutores"), admin(), caso[0]))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("VALIDACION"))
					.andExpect(jsonPath("$.detalles[0].campo").value(caso[1]));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void propiedadesProhibidasDan400SolicitudInvalida() throws Exception {
		for (String extra : new String[] { "\"familiaId\":\"" + UUID.randomUUID() + "\"",
				"\"usuarioId\":\"" + UUID.randomUUID() + "\"", "\"escuelaId\":\"" + UUID.randomUUID() + "\"",
				"\"activo\":false", "\"id\":\"" + UUID.randomUUID() + "\"", "\"cualquiera\":1" }) {
			mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/tutores"), admin(),
					"{\"nombre\":\"Ana\",\"apellido\":\"Perez\"," + extra + "}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
			mvc.perform(cuerpo(put("/api/admin/tutores/" + tutorId), admin(),
					"{\"nombre\":\"Ana\",\"apellido\":\"Perez\"," + extra + "}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void familiaInexistenteDa404ConElCodigoDeFamilia() throws Exception {
		when(servicio.crear(any(), any(), any(), any())).thenThrow(FamiliaAdminService.familiaNoEncontrada());

		mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/tutores"), admin(), VALIDO))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("FAMILIA_NO_ENCONTRADA"));
	}

	@Test
	void unIdDeFamiliaQueNoEsUuidDa400SolicitudInvalida() throws Exception {
		mvc.perform(cuerpo(post("/api/admin/familias/no-es-uuid/tutores"), admin(), VALIDO))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		verifyNoInteractions(servicio);
	}

	// ---------- familia inactiva (N3) ----------

	@Test
	void postYPutEnFamiliaInactivaDan409FamiliaInactivaSinIdsEnElCuerpo() throws Exception {
		when(servicio.crear(any(), any(), any(), any())).thenThrow(FamiliaAdminService.familiaInactiva());
		when(servicio.actualizar(any(), any(), any(), any())).thenThrow(FamiliaAdminService.familiaInactiva());

		String post = mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/tutores"), admin(), VALIDO))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("FAMILIA_INACTIVA"))
				.andReturn().getResponse().getContentAsString();
		String put = mvc.perform(cuerpo(put("/api/admin/tutores/" + tutorId), admin(), VALIDO))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("FAMILIA_INACTIVA"))
				.andReturn().getResponse().getContentAsString();

		assertThat(post).doesNotContain(familiaId.toString()).doesNotContain(tutorId.toString())
				.doesNotContain(escuelaId.toString());
		assertThat(put).doesNotContain(familiaId.toString()).doesNotContain(tutorId.toString())
				.doesNotContain(escuelaId.toString());
	}

	@Test
	void getDeUnTutorDeUnaFamiliaInactivaDa200() throws Exception {
		when(servicio.obtener(any(), eq(tutorId))).thenReturn(respuesta());

		mvc.perform(get("/api/admin/tutores/" + tutorId).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(tutorId.toString()));
	}

	// ---------- obtener / actualizar ----------

	@Test
	void obtenerDevuelve200ConElDetalleSinUsuarioNiEscuela() throws Exception {
		when(servicio.obtener(any(), eq(tutorId))).thenReturn(respuesta());

		mvc.perform(get("/api/admin/tutores/" + tutorId).cookie(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.familiaId").value(familiaId.toString()))
				.andExpect(jsonPath("$.dni").value("30111222"))
				.andExpect(jsonPath("$.activo").value(true))
				.andExpect(jsonPath("$.usuarioId").doesNotExist())
				.andExpect(jsonPath("$.escuelaId").doesNotExist());
		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).obtener(actor.capture(), eq(tutorId));
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
	}

	@Test
	void tutorAjenoYAleatorioDanExactamenteElMismoCuerpo404() throws Exception {
		when(servicio.obtener(any(), any())).thenThrow(TutorAdminService.tutorNoEncontrado());

		String ajeno = mvc.perform(get("/api/admin/tutores/" + tutorId).cookie(admin()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("TUTOR_NO_ENCONTRADO"))
				.andReturn().getResponse().getContentAsString();
		String aleatorio = mvc.perform(get("/api/admin/tutores/" + UUID.randomUUID()).cookie(admin()))
				.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

		assertThat(ajeno).isEqualTo(aleatorio);
		assertThat(ajeno).contains("El tutor no existe.");
	}

	@Test
	void unIdDeTutorQueNoEsUuidDa400SolicitudInvalida() throws Exception {
		mvc.perform(get("/api/admin/tutores/no-es-uuid").cookie(admin())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
	}

	@Test
	void actualizarDevuelve200YElDniInvalidoDa400() throws Exception {
		when(servicio.actualizar(any(), eq(tutorId), any(), any())).thenReturn(respuesta());

		mvc.perform(cuerpo(put("/api/admin/tutores/" + tutorId), admin(), VALIDO)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(tutorId.toString()));
		mvc.perform(cuerpo(put("/api/admin/tutores/" + tutorId), admin(),
				"{\"nombre\":\"Ana\",\"apellido\":\"Perez\",\"dni\":\"12ab\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detalles[0].campo").value("dni"));
	}

	@Test
	void actualizarUnTutorAjenoDa404() throws Exception {
		when(servicio.actualizar(any(), any(), any(), any())).thenThrow(TutorAdminService.tutorNoEncontrado());

		mvc.perform(cuerpo(put("/api/admin/tutores/" + tutorId), admin(), VALIDO)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("TUTOR_NO_ENCONTRADO"));
	}

	@Test
	void elParametroEscuelaIdSeIgnoraYLaEscuelaSaleDelToken() throws Exception {
		when(servicio.obtener(any(), any())).thenReturn(respuesta());

		mvc.perform(get("/api/admin/tutores/" + tutorId + "?escuelaId=" + UUID.randomUUID()).cookie(admin()))
				.andExpect(status().isOk());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).obtener(actor.capture(), any());
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
	}

	// ---------- 405 ----------

	@Test
	void deleteYPatchDeUnTutorDan405MetodoNoPermitido() throws Exception {
		mvc.perform(conCsrf(delete("/api/admin/tutores/" + tutorId)).cookie(admin()))
				.andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
		mvc.perform(cuerpo(patch("/api/admin/tutores/" + tutorId), admin(), VALIDO))
				.andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
		mvc.perform(conCsrf(delete("/api/admin/familias/" + familiaId + "/tutores")).cookie(admin()))
				.andExpect(status().isMethodNotAllowed());
		verifyNoInteractions(servicio);
	}

	// ---------- seguridad ----------

	@Test
	void anonimoRecibe401EnTodasLasRutas() throws Exception {
		mvc.perform(get("/api/admin/tutores/" + tutorId)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		mvc.perform(conCsrf(post("/api/admin/familias/" + familiaId + "/tutores"))
				.contentType(MediaType.APPLICATION_JSON).content(VALIDO)).andExpect(status().isUnauthorized());
		mvc.perform(conCsrf(put("/api/admin/tutores/" + tutorId)).contentType(MediaType.APPLICATION_JSON)
				.content(VALIDO)).andExpect(status().isUnauthorized());
		verifyNoInteractions(servicio);
	}

	@Test
	void familiaRecibe403AccesoDenegadoEnTodasLasRutas() throws Exception {
		Cookie familia = familiaSesion();
		mvc.perform(get("/api/admin/tutores/" + tutorId).cookie(familia)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/tutores"), familia, VALIDO))
				.andExpect(status().isForbidden());
		mvc.perform(cuerpo(put("/api/admin/tutores/" + tutorId), familia, VALIDO)).andExpect(status().isForbidden());
		verifyNoInteractions(servicio);
	}

	@Test
	void lasMutacionesSinCsrfDan403CsrfInvalido() throws Exception {
		mvc.perform(post("/api/admin/familias/" + familiaId + "/tutores").cookie(admin())
				.contentType(MediaType.APPLICATION_JSON).content(VALIDO)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(put("/api/admin/tutores/" + tutorId).cookie(admin()).contentType(MediaType.APPLICATION_JSON)
				.content(VALIDO)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		verifyNoInteractions(servicio);
	}

	@Test
	void activarYDesactivarExigenLaRevisionDelTutor() throws Exception {
		for (String comando : new String[] { "activar", "desactivar" }) {
			mvc.perform(crudo(post("/api/admin/tutores/" + tutorId + "/" + comando), admin(), "{}"))
					.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void comandosDeEstadoRecibenActorDelJwtYSoloLaRevision() throws Exception {
		var actor = new UsuarioAutenticado(adminId, escuelaId, null, Rol.ADMIN);
		when(servicio.desactivar(eq(actor), eq(tutorId), eq(4L), any())).thenReturn(
				new TutorRespuesta(tutorId, familiaId, "Ana", "Perez", null, null, null, null, false, "5", "VERIFICADO", null, "ADMIN"));
		when(servicio.activar(eq(actor), eq(tutorId), eq(5L), any())).thenReturn(
				new TutorRespuesta(tutorId, familiaId, "Ana", "Perez", null, null, null, null, true, "6", "VERIFICADO", null, "ADMIN"));
		mvc.perform(crudo(post("/api/admin/tutores/" + tutorId + "/desactivar"), admin(),
				"{\"versionEsperada\":\"4\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.activo").value(false)).andExpect(jsonPath("$.version").value("5"))
				.andExpect(jsonPath("$.escuelaId").doesNotExist());
		mvc.perform(crudo(post("/api/admin/tutores/" + tutorId + "/activar"), admin(),
				"{\"versionEsperada\":\"5\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.activo").value(true)).andExpect(jsonPath("$.version").value("6"));
		verify(servicio).desactivar(eq(actor), eq(tutorId), eq(4L), any());
		verify(servicio).activar(eq(actor), eq(tutorId), eq(5L), any());
	}

	@Test
	void losComandosDeEstadoRechazanFamiliaFaltaDeCsrfOIdentidadEnElBody() throws Exception {
		String revision = "{\"versionEsperada\":\"0\"}";
		for (String comando : new String[] { "activar", "desactivar" }) {
			String ruta = "/api/admin/tutores/" + tutorId + "/" + comando;
			mvc.perform(crudo(post(ruta), familiaSesion(), revision)).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
			mvc.perform(post(ruta).cookie(admin()).contentType(MediaType.APPLICATION_JSON).content(revision))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
			mvc.perform(crudo(post(ruta), admin(),
					"{\"versionEsperada\":\"0\",\"escuelaId\":\"" + escuelaId + "\"}")).andExpect(status().isBadRequest());
		}
		verifyNoInteractions(servicio);
	}

	// ---------- revisiones (FE-05/U4) ----------

	private static final String DATOS_ALTA = "\"nombre\":\"Ana\",\"apellido\":\"Perez\"";

	@Test
	void laRespuestaDelTutorIncluyeSuRevisionComoCadena() throws Exception {
		when(servicio.obtener(any(), eq(tutorId))).thenReturn(respuesta());

		mvc.perform(get("/api/admin/tutores/" + tutorId).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value("0"));
	}

	@Test
	void elAltaExigeLaRevisionDeLaFamiliaYLaPasaAlServicio() throws Exception {
		when(servicio.crear(any(), eq(familiaId), any(), any())).thenReturn(respuesta());

		mvc.perform(crudo(post("/api/admin/familias/" + familiaId + "/tutores"), admin(),
				"{" + DATOS_ALTA + ",\"versionFamiliaEsperada\":\"7\"}")).andExpect(status().isCreated());

		ArgumentCaptor<TutorSolicitud> solicitud = ArgumentCaptor.forClass(TutorSolicitud.class);
		verify(servicio).crear(any(), eq(familiaId), solicitud.capture(), any(DatosSolicitud.class));
		assertThat(solicitud.getValue().versionFamiliaEsperada()).isEqualTo(7L);
	}

	@Test
	void laEdicionExigeLasDosRevisionesYLasPasaAlServicio() throws Exception {
		when(servicio.actualizar(any(), eq(tutorId), any(), any())).thenReturn(respuesta());

		mvc.perform(crudo(put("/api/admin/tutores/" + tutorId), admin(),
				"{" + DATOS_ALTA + ",\"versionEsperada\":\"4\",\"versionFamiliaEsperada\":\"2\"}")).andExpect(status().isOk());

		ArgumentCaptor<TutorActualizacion> solicitud = ArgumentCaptor.forClass(TutorActualizacion.class);
		verify(servicio).actualizar(any(), eq(tutorId), solicitud.capture(), any(DatosSolicitud.class));
		assertThat(solicitud.getValue().versionEsperada()).isEqualTo(4L);
		assertThat(solicitud.getValue().versionFamiliaEsperada()).isEqualTo(2L);
	}

	@Test
	void unaRevisionAusenteONulaDa400ValidacionNombrandoElCampoYNoLlegaAlServicio() throws Exception {
		String[][] casos = {
				{ "post", "{" + DATOS_ALTA + "}", "versionFamiliaEsperada" },
				{ "post", "{" + DATOS_ALTA + ",\"versionFamiliaEsperada\":null}", "versionFamiliaEsperada" },
				{ "put", "{" + DATOS_ALTA + ",\"versionFamiliaEsperada\":\"0\"}", "versionEsperada" },
				{ "put", "{" + DATOS_ALTA + ",\"versionEsperada\":null,\"versionFamiliaEsperada\":\"0\"}", "versionEsperada" },
				{ "put", "{" + DATOS_ALTA + ",\"versionEsperada\":\"0\"}", "versionFamiliaEsperada" } };
		for (String[] caso : casos) {
			var req = caso[0].equals("post") ? post("/api/admin/familias/" + familiaId + "/tutores")
					: put("/api/admin/tutores/" + tutorId);
			mvc.perform(crudo(req, admin(), caso[1])).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("VALIDACION"))
					.andExpect(jsonPath("$.detalles[0].campo").value(caso[2]));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void unaRevisionConFormatoInvalidoDa400SolicitudInvalidaSinEcoDelValor() throws Exception {
		for (String valor : new String[] { "0", "-1", "\"-1\"", "\"abc\"", "\"1.5\"", "\" 1\"", "\"\"", "true",
				"\"" + "9".repeat(20) + "\"", "\"9223372036854775808\"" }) {
			for (String campo : new String[] { "versionEsperada", "versionFamiliaEsperada" }) {
				String otra = campo.equals("versionEsperada") ? "versionFamiliaEsperada" : "versionEsperada";
				String cuerpo = "{" + DATOS_ALTA + ",\"" + campo + "\":" + valor + ",\"" + otra + "\":\"0\"}";
				String respuesta = mvc.perform(crudo(put("/api/admin/tutores/" + tutorId), admin(), cuerpo))
						.andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
						.andReturn().getResponse().getContentAsString();
				assertThat(respuesta).doesNotContain("abc").doesNotContain("9223372036854775808");
			}
			mvc.perform(crudo(post("/api/admin/familias/" + familiaId + "/tutores"), admin(),
					"{" + DATOS_ALTA + ",\"versionFamiliaEsperada\":" + valor + "}")).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void laRevisionObsoletaDa409EdicionDesactualizadaConMensajeFijoSinIdsNiDatos() throws Exception {
		when(servicio.crear(any(), any(), any(), any())).thenThrow(FamiliaAdminService.edicionDesactualizada());
		when(servicio.actualizar(any(), any(), any(), any())).thenThrow(FamiliaAdminService.edicionDesactualizada());

		String alta = mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/tutores"), admin(), VALIDO))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA"))
				.andReturn().getResponse().getContentAsString();
		String edicion = mvc.perform(cuerpo(put("/api/admin/tutores/" + tutorId), admin(), VALIDO))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA"))
				.andReturn().getResponse().getContentAsString();
		for (String cuerpo : new String[] { alta, edicion }) {
			assertThat(cuerpo).doesNotContain(tutorId.toString(), familiaId.toString(), "Ana", "Perez");
		}
	}

	@Test
	void unConflictoDeJpaDa409EdicionDesactualizadaSinFiltrarSuMensaje() throws Exception {
		when(servicio.actualizar(any(), any(), any(), any()))
				.thenThrow(new ObjectOptimisticLockingFailureException(Tutor.class, tutorId));

		String cuerpo = mvc.perform(cuerpo(put("/api/admin/tutores/" + tutorId), admin(), VALIDO))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA"))
				.andReturn().getResponse().getContentAsString();
		assertThat(cuerpo).doesNotContain(tutorId.toString(), "Tutor");
	}
}
