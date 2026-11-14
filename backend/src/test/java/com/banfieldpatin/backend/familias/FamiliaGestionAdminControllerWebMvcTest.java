package com.banfieldpatin.backend.familias;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.FiltroEstado;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.dto.FamiliaAdminResumen;
import com.banfieldpatin.backend.familias.dto.FamiliaDetalle;
import com.banfieldpatin.backend.familias.dto.FamiliaSolicitud;
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

@WebMvcTest(controllers = FamiliaGestionAdminController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class FamiliaGestionAdminControllerWebMvcTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	FamiliaAdminService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();

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

	private MockHttpServletRequestBuilder cuerpo(MockHttpServletRequestBuilder req, Cookie sesion, String json)
			throws Exception {
		return conCsrf(req.contentType(MediaType.APPLICATION_JSON).content(json)).cookie(sesion);
	}

	private FamiliaDetalle detalle(boolean activa) {
		return new FamiliaDetalle(familiaId, "Perez", activa, List.of(), "0");
	}

	// ---------- listado ----------

	@Test
	void listadoPorDefectoEsTodosYDevuelveElResumenAdministrativo() throws Exception {
		when(servicio.listar(any(), any(), any(), any())).thenReturn(new Pagina<>(
				List.of(new FamiliaAdminResumen(familiaId, "Perez", false, 0, 0)), 0, 20, 1));

		mvc.perform(get("/api/admin/familias/listado").cookie(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.contenido[0].id").value(familiaId.toString()))
				.andExpect(jsonPath("$.contenido[0].nombreReferencia").value("Perez"))
				.andExpect(jsonPath("$.contenido[0].activa").value(false))
				.andExpect(jsonPath("$.contenido[0].cantidadTutores").value(0))
				.andExpect(jsonPath("$.contenido[0].cantidadDeportistasActivos").value(0))
				.andExpect(jsonPath("$.contenido[0].escuelaId").doesNotExist())
				.andExpect(jsonPath("$.totalElementos").value(1));

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).listar(actor.capture(), eq(FiltroEstado.TODOS), eq(""), any(Pageable.class));
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
	}

	@Test
	void listadoAceptaLosTresEstadosEscapaLaBusquedaYAcotaLaPagina() throws Exception {
		when(servicio.listar(any(), any(), any(), any())).thenReturn(new Pagina<>(List.of(), 0, 100, 0));

		mvc.perform(get("/api/admin/familias/listado").param("estado", "INACTIVOS").param("busqueda", " 50%_ ")
				.param("size", "1000").param("page", "-3").cookie(admin())).andExpect(status().isOk());

		ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
		verify(servicio).listar(any(), eq(FiltroEstado.INACTIVOS), eq("50!%!_"), pagina.capture());
		assertThat(pagina.getValue().getPageSize()).isEqualTo(100);
		assertThat(pagina.getValue().getPageNumber()).isZero();
	}

	@Test
	void listadoConEstadoInvalidoDa400SolicitudInvalida() throws Exception {
		mvc.perform(get("/api/admin/familias/listado?estado=x").cookie(admin()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		verifyNoInteractions(servicio);
	}

	@Test
	void elParametroEscuelaIdSeIgnoraYLaEscuelaSaleDelToken() throws Exception {
		when(servicio.listar(any(), any(), any(), any())).thenReturn(new Pagina<>(List.of(), 0, 20, 0));

		mvc.perform(get("/api/admin/familias/listado?escuelaId=" + UUID.randomUUID()).cookie(admin()))
				.andExpect(status().isOk());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).listar(actor.capture(), any(), any(), any());
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
	}

	// ---------- crear ----------

	@Test
	void crearDevuelve201ConLocationYDetalleSinEscuelaId() throws Exception {
		when(servicio.crear(any(), any(), any())).thenReturn(detalle(true));

		mvc.perform(cuerpo(post("/api/admin/familias"), admin(), "{\"nombreReferencia\":\"  Perez  \"}"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/admin/familias/" + familiaId))
				.andExpect(jsonPath("$.id").value(familiaId.toString()))
				.andExpect(jsonPath("$.nombreReferencia").value("Perez"))
				.andExpect(jsonPath("$.activa").value(true))
				.andExpect(jsonPath("$.tutores").isArray())
				.andExpect(jsonPath("$.escuelaId").doesNotExist());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		ArgumentCaptor<FamiliaSolicitud> solicitud = ArgumentCaptor.forClass(FamiliaSolicitud.class);
		verify(servicio).crear(actor.capture(), solicitud.capture(), any(DatosSolicitud.class));
		assertThat(actor.getValue().id()).isEqualTo(adminId);
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
		assertThat(solicitud.getValue().nombreReferencia()).isEqualTo("Perez");
	}

	@Test
	void crearConNombreEnBlancoFaltanteOLargoDa400ValidacionNombrandoElCampo() throws Exception {
		for (String json : new String[] { "{\"nombreReferencia\":\"   \"}", "{}",
				"{\"nombreReferencia\":\"" + "a".repeat(151) + "\"}" }) {
			mvc.perform(cuerpo(post("/api/admin/familias"), admin(), json))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("VALIDACION"))
					.andExpect(jsonPath("$.detalles[0].campo").value("nombreReferencia"));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void crearConPropiedadesProhibidasDa400SolicitudInvalida() throws Exception {
		for (String extra : new String[] { "\"escuelaId\":\"" + UUID.randomUUID() + "\"", "\"activa\":false",
				"\"activo\":true", "\"estado\":\"ACTIVOS\"", "\"familiaId\":\"" + UUID.randomUUID() + "\"" }) {
			mvc.perform(cuerpo(post("/api/admin/familias"), admin(), "{\"nombreReferencia\":\"Perez\"," + extra + "}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		}
		verifyNoInteractions(servicio);
	}

	// ---------- detalle ----------

	@Test
	void obtenerDevuelve200() throws Exception {
		when(servicio.obtener(any(), eq(familiaId))).thenReturn(detalle(false));

		mvc.perform(get("/api/admin/familias/" + familiaId).cookie(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.activa").value(false))
				.andExpect(jsonPath("$.escuelaId").doesNotExist());
	}

	@Test
	void obtenerInexistenteDa404ConElCodigoDeFamilia() throws Exception {
		when(servicio.obtener(any(), any())).thenThrow(FamiliaAdminService.familiaNoEncontrada());

		mvc.perform(get("/api/admin/familias/" + UUID.randomUUID()).cookie(admin()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("FAMILIA_NO_ENCONTRADA"))
				.andExpect(jsonPath("$.mensaje").value("La familia no existe."));
	}

	@Test
	void unIdQueNoEsUuidDa400SolicitudInvalida() throws Exception {
		mvc.perform(get("/api/admin/familias/no-es-uuid").cookie(admin()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
	}

	@Test
	void elLiteralListadoGanaAlIdYNoLlegaAlDetalle() throws Exception {
		when(servicio.listar(any(), any(), any(), any())).thenReturn(new Pagina<>(List.of(), 0, 20, 0));

		mvc.perform(get("/api/admin/familias/listado").cookie(admin())).andExpect(status().isOk());

		verify(servicio).listar(any(), any(), any(), any());
		org.mockito.Mockito.verifyNoMoreInteractions(servicio);
	}

	@Test
	void u1DetalleIncluyeVersionDecimal() throws Exception {
		when(servicio.obtener(any(), eq(familiaId))).thenReturn(detalle(true));
		mvc.perform(get("/api/admin/familias/" + familiaId).cookie(admin()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.version").value("0"));
	}

	@Test
	void u1ActualizarSinVersionEs400() throws Exception {
		mvc.perform(cuerpo(put("/api/admin/familias/" + familiaId), admin(), "{\"nombreReferencia\":\"Perez\"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(servicio);
	}

	@Test
	void revisionesAusentesInvalidasYTiposNoCadenaSeRechazanEnTodosLosComandos() throws Exception {
		for (String valor : new String[] { null, "null", "0", "0.0", "true", "[]", "{}", "\"\"", "\"-1\"",
				"\" 0\"", "\"1.0\"", "\"1e2\"", "\"9223372036854775808\"", "\"١\"" }) {
			String version = valor == null ? "" : ",\"versionEsperada\":" + valor;
			mvc.perform(cuerpo(put("/api/admin/familias/" + familiaId), admin(), "{\"nombreReferencia\":\"Prueba\"" + version + "}"))
					.andExpect(status().isBadRequest());
			for (String accion : new String[] { "activar", "desactivar" }) {
				mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/" + accion), admin(),
						valor == null ? "{}" : "{\"versionEsperada\":" + valor + "}"))
						.andExpect(status().isBadRequest());
			}
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void estadoNoAceptaCuerpoAusenteNiPropiedadesAdicionales() throws Exception {
		for (String accion : new String[] { "activar", "desactivar" }) {
			mvc.perform(conCsrf(post("/api/admin/familias/" + familiaId + "/" + accion)).cookie(admin()))
					.andExpect(status().isBadRequest());
			mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/" + accion), admin(),
					"{\"versionEsperada\":\"0\",\"activa\":true}"))
					.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void conflictoOptimistaTieneRespuestaFijaSinDatosNiVersionVigente() throws Exception {
		when(servicio.actualizar(any(), any(), any(), any())).thenThrow(
				new org.springframework.orm.ObjectOptimisticLockingFailureException("Familia", familiaId));
		String json = mvc.perform(cuerpo(put("/api/admin/familias/" + familiaId), admin(),
				"{\"nombreReferencia\":\"Prueba\",\"versionEsperada\":\"0\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA"))
				.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(json).doesNotContain(familiaId.toString(), "Prueba", "version", "nombreReferencia");
	}

	@Test
	void contencionPesimistaConservaElCodigoDistinto() throws Exception {
		when(servicio.actualizar(any(), any(), any(), any())).thenThrow(
				new org.springframework.dao.CannotAcquireLockException("Mensaje interno sintetico"));
		mvc.perform(cuerpo(put("/api/admin/familias/" + familiaId), admin(),
				"{\"nombreReferencia\":\"Prueba\",\"versionEsperada\":\"0\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CONFLICTO_CONCURRENCIA"));
	}

	@Test
	void mfaPendienteNoAccedeAlDetalleNiALosComandos() throws Exception {
		Cookie pendiente = new Cookie("BP_SESION", tokens.emitirMfaPendiente(adminId, escuelaId));
		mvc.perform(get("/api/admin/familias/" + familiaId).cookie(pendiente)).andExpect(status().isForbidden());
		mvc.perform(cuerpo(put("/api/admin/familias/" + familiaId), pendiente,
				"{\"nombreReferencia\":\"Prueba\",\"versionEsperada\":\"0\"}"))
				.andExpect(status().isForbidden());
		for (String accion : new String[] { "activar", "desactivar" }) {
			mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/" + accion), pendiente,
					"{\"versionEsperada\":\"0\"}" )).andExpect(status().isForbidden());
		}
		verifyNoInteractions(servicio);
	}

	// ---------- actualizar ----------

	@Test
	void actualizarDevuelve200() throws Exception {
		when(servicio.actualizar(any(), eq(familiaId), any(), any())).thenReturn(detalle(true));

		mvc.perform(cuerpo(put("/api/admin/familias/" + familiaId), admin(), "{\"nombreReferencia\":\"Perez\",\"versionEsperada\":\"0\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nombreReferencia").value("Perez"));
	}

	@Test
	void actualizarConActivaFalseDa400SolicitudInvalidaSinTocarElServicio() throws Exception {
		mvc.perform(cuerpo(put("/api/admin/familias/" + familiaId), admin(),
				"{\"nombreReferencia\":\"Perez\",\"activa\":false}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		verifyNoInteractions(servicio);
	}

	@Test
	void actualizarConNombreEnBlancoDa400Validacion() throws Exception {
		mvc.perform(cuerpo(put("/api/admin/familias/" + familiaId), admin(), "{\"nombreReferencia\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"));
		verifyNoInteractions(servicio);
	}

	@Test
	void actualizarDeOtraEscuelaDa404() throws Exception {
		when(servicio.actualizar(any(), any(), any(), any())).thenThrow(FamiliaAdminService.familiaNoEncontrada());

		mvc.perform(cuerpo(put("/api/admin/familias/" + familiaId), admin(), "{\"nombreReferencia\":\"X\",\"versionEsperada\":\"0\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("FAMILIA_NO_ENCONTRADA"));
	}

	// ---------- activar / desactivar ----------

	@Test
	void activarYDesactivarDevuelven200ConElDetalle() throws Exception {
		when(servicio.activar(any(), eq(familiaId), eq(0L), any())).thenReturn(detalle(true));
		when(servicio.desactivar(any(), eq(familiaId), eq(0L), any())).thenReturn(detalle(false));

		mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/activar"), admin(), "{\"versionEsperada\":\"0\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.activa").value(true));
		mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/desactivar"), admin(), "{\"versionEsperada\":\"0\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.activa").value(false));
	}

	@Test
	void activarDeOtraEscuelaDa404() throws Exception {
		when(servicio.activar(any(), any(), any(), any())).thenThrow(
				new ExcepcionNegocio(HttpStatus.NOT_FOUND, "FAMILIA_NO_ENCONTRADA", "La familia no existe."));

		mvc.perform(cuerpo(post("/api/admin/familias/" + familiaId + "/activar"), admin(), "{\"versionEsperada\":\"0\"}"))
				.andExpect(status().isNotFound());
	}

	// ---------- seguridad ----------

	@Test
	void anonimoRecibe401EnTodasLasRutas() throws Exception {
		mvc.perform(get("/api/admin/familias/listado")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		mvc.perform(get("/api/admin/familias/" + familiaId)).andExpect(status().isUnauthorized());
		mvc.perform(conCsrf(post("/api/admin/familias")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"nombreReferencia\":\"x\"}")).andExpect(status().isUnauthorized());
		verifyNoInteractions(servicio);
	}

	@Test
	void familiaRecibe403ACCESO_DENEGADOEnTodasLasRutas() throws Exception {
		Cookie familia = familiaSesion();
		mvc.perform(get("/api/admin/familias/listado").cookie(familia)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		mvc.perform(get("/api/admin/familias/" + familiaId).cookie(familia)).andExpect(status().isForbidden());
		mvc.perform(cuerpo(post("/api/admin/familias"), familia, "{\"nombreReferencia\":\"x\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(cuerpo(put("/api/admin/familias/" + familiaId), familia, "{\"nombreReferencia\":\"x\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(conCsrf(post("/api/admin/familias/" + familiaId + "/desactivar")).cookie(familia))
				.andExpect(status().isForbidden());
		verifyNoInteractions(servicio);
	}

	@Test
	void lasMutacionesSinCsrfDan403CsrfInvalido() throws Exception {
		mvc.perform(post("/api/admin/familias").cookie(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"nombreReferencia\":\"x\"}")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(put("/api/admin/familias/" + familiaId).cookie(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"nombreReferencia\":\"x\"}")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(post("/api/admin/familias/" + familiaId + "/activar").cookie(admin()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(post("/api/admin/familias/" + familiaId + "/desactivar").cookie(admin()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		verifyNoInteractions(servicio);
	}

	@Test
	void noExisteBorradoDeFamilias() throws Exception {
		mvc.perform(conCsrf(delete("/api/admin/familias/" + familiaId)).cookie(admin()))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
		verifyNoInteractions(servicio);
	}

	// ---------- DNI de los tutores anidados (O8) ----------

	@Test
	void elDetalleDeFamiliaEnmascaraElDniDeSusTutoresYNoTraeElCompleto() throws Exception {
		UUID escuela = UUID.randomUUID();
		var conDni = com.banfieldpatin.backend.familias.tutores.Tutor.crear(escuela, familiaId, "Ana", "Gomez", "27123456",
				null, null, "Madre");
		var sinDni = com.banfieldpatin.backend.familias.tutores.Tutor.crear(escuela, familiaId, "Luis", "Gomez", null, null,
				null, null);
		when(servicio.obtener(any(), eq(familiaId))).thenReturn(new FamiliaDetalle(familiaId, "Gomez", true,
				List.of(com.banfieldpatin.backend.familias.tutores.dto.TutorResumen.de(conDni),
						com.banfieldpatin.backend.familias.tutores.dto.TutorResumen.de(sinDni)), "0"));

		String cuerpo = mvc.perform(get("/api/admin/familias/" + familiaId).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.tutores[0].dniEnmascarado").value("•••••456"))
				.andExpect(jsonPath("$.tutores[0].dni").doesNotExist())
				.andExpect(jsonPath("$.tutores[1].dniEnmascarado").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.tutores[1].dni").doesNotExist()).andReturn().getResponse()
				.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

		tools.jackson.databind.JsonNode arbol = new tools.jackson.databind.json.JsonMapper().readTree(cuerpo);
		assertThat(com.banfieldpatin.backend.JsonDeContrato.claves(arbol)).doesNotContain("dni");
		assertThat(com.banfieldpatin.backend.JsonDeContrato.filtracionesDeDni(arbol, List.of("27123456"))).isEmpty();
	}
}
