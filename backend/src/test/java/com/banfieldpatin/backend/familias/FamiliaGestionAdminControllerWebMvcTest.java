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
		return new FamiliaDetalle(familiaId, "Perez", activa, List.of());
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

	// ---------- actualizar ----------

	@Test
	void actualizarDevuelve200() throws Exception {
		when(servicio.actualizar(any(), eq(familiaId), any(), any())).thenReturn(detalle(true));

		mvc.perform(cuerpo(put("/api/admin/familias/" + familiaId), admin(), "{\"nombreReferencia\":\"Perez\"}"))
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

		mvc.perform(cuerpo(put("/api/admin/familias/" + familiaId), admin(), "{\"nombreReferencia\":\"X\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("FAMILIA_NO_ENCONTRADA"));
	}

	// ---------- activar / desactivar ----------

	@Test
	void activarYDesactivarDevuelven200ConElDetalle() throws Exception {
		when(servicio.activar(any(), eq(familiaId), any())).thenReturn(detalle(true));
		when(servicio.desactivar(any(), eq(familiaId), any())).thenReturn(detalle(false));

		mvc.perform(conCsrf(post("/api/admin/familias/" + familiaId + "/activar")).cookie(admin()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.activa").value(true));
		mvc.perform(conCsrf(post("/api/admin/familias/" + familiaId + "/desactivar")).cookie(admin()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.activa").value(false));
	}

	@Test
	void activarDeOtraEscuelaDa404() throws Exception {
		when(servicio.activar(any(), any(), any())).thenThrow(
				new ExcepcionNegocio(HttpStatus.NOT_FOUND, "FAMILIA_NO_ENCONTRADA", "La familia no existe."));

		mvc.perform(conCsrf(post("/api/admin/familias/" + familiaId + "/activar")).cookie(admin()))
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
}
