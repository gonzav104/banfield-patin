package com.banfieldpatin.backend.temporadas;

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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.seguridad.CookieBearerTokenResolver;
import com.banfieldpatin.backend.seguridad.CookieSesion;
import com.banfieldpatin.backend.seguridad.JwtConfig;
import com.banfieldpatin.backend.seguridad.ManejadorAccesoDenegadoJson;
import com.banfieldpatin.backend.seguridad.PuntoEntradaJson;
import com.banfieldpatin.backend.seguridad.SeguridadConfig;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.SesionVigenteDePrueba;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaActualizacion;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaRespuesta;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaSolicitud;
import com.banfieldpatin.backend.temporadas.dto.PlantelRespuesta;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

@WebMvcTest(controllers = DeportistaTemporadaAdminController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class DeportistaTemporadaAdminControllerWebMvcTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	DeportistaTemporadaAdminService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UUID deportistaId = UUID.randomUUID();
	private final UUID temporadaId = UUID.randomUUID();
	private final String base = "/api/admin/deportistas/" + deportistaId + "/temporadas";

	private Cookie admin() {
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

	private MockHttpServletRequestBuilder cuerpo(MockHttpServletRequestBuilder req, Cookie sesion, String json) throws Exception {
		return conCsrf(req.contentType(MediaType.APPLICATION_JSON).content(json)).cookie(sesion);
	}

	private DeportistaTemporadaRespuesta respuesta() {
		return new DeportistaTemporadaRespuesta(UUID.randomUUID(), deportistaId, temporadaId, 2027, "Sub 12", "Femenino",
				"A", "007", EstadoDeportivo.ACTIVO);
	}

	private String altaJson(String extra) {
		return "{\"temporadaId\":\"" + temporadaId + "\",\"categoria\":\"Sub 12\"" + extra + "}";
	}

	// ---------- alta ----------

	@Test
	void altaDevuelve201ConLocationDelParYSinCamposInternos() throws Exception {
		when(servicio.crear(any(), eq(deportistaId), any(), any())).thenReturn(respuesta());

		mvc.perform(cuerpo(post(base), admin(), altaJson(",\"numeroCorredor\":\"007\""))).andExpect(status().isCreated())
				.andExpect(header().string("Location", base + "/" + temporadaId))
				.andExpect(jsonPath("$.numeroCorredor").value("007")).andExpect(jsonPath("$.anio").value(2027))
				.andExpect(jsonPath("$.estadoDeportivo").value("ACTIVO")).andExpect(jsonPath("$.escuelaId").doesNotExist())
				.andExpect(jsonPath("$.dni").doesNotExist());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		ArgumentCaptor<DeportistaTemporadaSolicitud> solicitud = ArgumentCaptor.forClass(DeportistaTemporadaSolicitud.class);
		verify(servicio).crear(actor.capture(), eq(deportistaId), solicitud.capture(), any());
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
		assertThat(solicitud.getValue().numeroCorredor()).isEqualTo("007");
		assertThat(solicitud.getValue().temporadaId()).isEqualTo(temporadaId);
	}

	@Test
	void altaConTextosInvalidosDa400ValidacionSinLlamarAlServicio() throws Exception {
		for (String extra : List.of(",\"rama\":\"" + "r".repeat(41) + "\"", ",\"numeroCorredor\":\"" + "9".repeat(31) + "\"",
				",\"divisional\":\"x\\u0000\"")) {
			mvc.perform(cuerpo(post(base), admin(), altaJson(extra))).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("VALIDACION"));
		}
		mvc.perform(cuerpo(post(base), admin(), "{\"categoria\":\"Sub 12\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"));
		verifyNoInteractions(servicio);
	}

	@Test
	void altaConCuerpoIlegibleEstadoDesconocidoOPropiedadesDelServidorDa400SolicitudInvalida() throws Exception {
		for (String cuerpo : List.of(altaJson(",\"estadoDeportivo\":\"RETIRADO\""), altaJson(",\"escuelaId\":\"x\""),
				altaJson(",\"deportistaId\":\"x\""), altaJson(",\"id\":\"x\""), "{\"temporadaId\":\"no-uuid\"}", "{\"temporadaId\":",
				"[]")) {
			mvc.perform(cuerpo(post(base), admin(), cuerpo)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void altaTraduceLosConflictosYLosNoEncontradosDelServicio() throws Exception {
		when(servicio.crear(any(), eq(deportistaId), any(), any())).thenThrow(new ExcepcionNegocio(
				org.springframework.http.HttpStatus.CONFLICT, "NUMERO_CORREDOR_DUPLICADO", "Ya existe."));

		mvc.perform(cuerpo(post(base), admin(), altaJson(""))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("NUMERO_CORREDOR_DUPLICADO"));
	}

	// ---------- consulta ----------

	@Test
	void historialDetalleYPlantelDevuelvenLosDatosDelServicio() throws Exception {
		when(servicio.historial(any(), eq(deportistaId))).thenReturn(List.of(respuesta()));
		when(servicio.obtener(any(), eq(deportistaId), eq(temporadaId))).thenReturn(respuesta());
		when(servicio.plantel(any(), eq(temporadaId), any(), any())).thenReturn(new Pagina<>(List.of(new PlantelRespuesta(
				UUID.randomUUID(), deportistaId, temporadaId, "Ana", "Prueba", null, null, null, "007",
				EstadoDeportivo.ACTIVO)), 0, 20, 1));

		mvc.perform(get(base).cookie(admin())).andExpect(status().isOk()).andExpect(jsonPath("$[0].anio").value(2027));
		mvc.perform(get(base + "/" + temporadaId).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.categoria").value("Sub 12"));
		mvc.perform(get("/api/admin/temporadas/" + temporadaId + "/deportistas").cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.contenido[0].apellido").value("Prueba")).andExpect(jsonPath("$.contenido[0].dni").doesNotExist())
				.andExpect(jsonPath("$.totalElementos").value(1));
	}

	@Test
	void elPlantelPasaElFiltroDeEstadoYAcotaLaPagina() throws Exception {
		when(servicio.plantel(any(), any(), any(), any())).thenReturn(new Pagina<>(List.of(), 0, 100, 0));

		mvc.perform(get("/api/admin/temporadas/" + temporadaId + "/deportistas").param("estadoDeportivo", "SUSPENDIDO")
				.param("size", "1000").param("page", "-3").cookie(admin())).andExpect(status().isOk());

		ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
		verify(servicio).plantel(any(), eq(temporadaId), eq("SUSPENDIDO"), pagina.capture());
		assertThat(pagina.getValue().getPageSize()).isEqualTo(100);
		assertThat(pagina.getValue().getPageNumber()).isZero();
	}

	@Test
	void unIdQueNoEsUuidDa400() throws Exception {
		mvc.perform(get("/api/admin/deportistas/no-es-uuid/temporadas").cookie(admin())).andExpect(status().isBadRequest());
		mvc.perform(get(base + "/no-es-uuid").cookie(admin())).andExpect(status().isBadRequest());
		verifyNoInteractions(servicio);
	}

	// ---------- autenticacion, autorizacion y CSRF ----------

	@Test
	void sinSesionDa401YUnaFamiliaDa403EnLasCincoRutasSinLlegarAlServicio() throws Exception {
		String plantel = "/api/admin/temporadas/" + temporadaId + "/deportistas";
		for (Cookie sesion : new Cookie[] { null, familia() }) {
			int esperado = sesion == null ? 401 : 403;
			List<MockHttpServletRequestBuilder> pedidos = List.of(get(base), get(base + "/" + temporadaId), get(plantel),
					conCsrf(post(base).contentType(MediaType.APPLICATION_JSON).content(altaJson(""))),
					conCsrf(put(base + "/" + temporadaId).contentType(MediaType.APPLICATION_JSON)
							.content("{\"estadoDeportivo\":\"ACTIVO\"}")));
			for (MockHttpServletRequestBuilder pedido : pedidos) {
				mvc.perform(sesion == null ? pedido : pedido.cookie(sesion)).andExpect(status().is(esperado));
			}
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void sinTokenCsrfLasEscriturasDan403() throws Exception {
		mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(altaJson("")).cookie(admin()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(put(base + "/" + temporadaId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"estadoDeportivo\":\"ACTIVO\"}").cookie(admin())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		verifyNoInteractions(servicio);
	}

	@Test
	void noExistenBorradoNiPatch() throws Exception {
		mvc.perform(conCsrf(delete(base + "/" + temporadaId)).cookie(admin())).andExpect(status().isMethodNotAllowed());
		mvc.perform(conCsrf(patch(base + "/" + temporadaId).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.cookie(admin())).andExpect(status().isMethodNotAllowed());
		verifyNoInteractions(servicio);
	}

	// ---------- edicion (la implementacion llega en la siguiente unidad) ----------

	@Test
	void editarDevuelve200ConLaFilaYNoAceptaLaIdentidadEnElCuerpo() throws Exception {
		when(servicio.actualizar(any(), eq(deportistaId), eq(temporadaId), any(), any())).thenReturn(respuesta());

		mvc.perform(cuerpo(put(base + "/" + temporadaId), admin(),
				"{\"categoria\":\" Sub  14 \",\"numeroCorredor\":\"008\",\"estadoDeportivo\":\"SUSPENDIDO\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").exists());

		ArgumentCaptor<DeportistaTemporadaActualizacion> a = ArgumentCaptor.forClass(DeportistaTemporadaActualizacion.class);
		verify(servicio).actualizar(any(), eq(deportistaId), eq(temporadaId), a.capture(), any());
		assertThat(a.getValue().categoria()).isEqualTo("Sub 14");
		assertThat(a.getValue().numeroCorredor()).isEqualTo("008");
		assertThat(a.getValue().estadoDeportivo()).isEqualTo(EstadoDeportivo.SUSPENDIDO);

		for (String extra : List.of("\"temporadaId\":\"" + temporadaId + "\"", "\"deportistaId\":\"x\"", "\"escuelaId\":\"x\"")) {
			mvc.perform(cuerpo(put(base + "/" + temporadaId), admin(), "{\"estadoDeportivo\":\"ACTIVO\"," + extra + "}"))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		}
		mvc.perform(cuerpo(put(base + "/" + temporadaId), admin(), "{\"categoria\":\"Sub 12\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"));
	}
}
