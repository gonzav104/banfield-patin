package com.banfieldpatin.backend.familias.portal;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.familias.portal.dto.TemporadaDeFamilia;
import com.banfieldpatin.backend.seguridad.CookieBearerTokenResolver;
import com.banfieldpatin.backend.seguridad.CookieSesion;
import com.banfieldpatin.backend.seguridad.JwtConfig;
import com.banfieldpatin.backend.seguridad.ManejadorAccesoDenegadoJson;
import com.banfieldpatin.backend.seguridad.PuntoEntradaJson;
import com.banfieldpatin.backend.seguridad.SeguridadConfig;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.SesionVigenteDePrueba;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.DeportistaTemporadaAdminService;
import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

@WebMvcTest(controllers = FamiliaPortalTemporadasController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class FamiliaPortalTemporadasControllerWebMvcTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	FamiliaPortalTemporadasService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final UUID usuarioId = UUID.randomUUID();
	private final UUID deportistaId = UUID.randomUUID();
	private final UUID temporadaId = UUID.randomUUID();
	private final String historial = "/api/familia/deportistas/" + deportistaId + "/temporadas";
	private final String detalle = historial + "/" + temporadaId;

	private Cookie familia() {
		return new Cookie("BP_SESION", tokens.emitir(usuarioId, Rol.FAMILIA, escuelaId, familiaId));
	}

	private Cookie admin() {
		return new Cookie("BP_SESION", tokens.emitir(UUID.randomUUID(), Rol.ADMIN, escuelaId, null));
	}

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder req) throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
		Cookie x = r.getCookie("XSRF-TOKEN");
		return req.cookie(x).header("X-XSRF-TOKEN", x.getValue());
	}

	private TemporadaDeFamilia fila() {
		return new TemporadaDeFamilia(temporadaId, (short) 2026, true, "Sub 12", "Femenino", "A", "007",
				EstadoDeportivo.SUSPENDIDO);
	}

	@Test
	void elHistorialDevuelveUnaListaConExactamenteLosOchoCamposYNingunoInterno() throws Exception {
		when(servicio.historial(any(), eq(deportistaId))).thenReturn(List.of(fila()));

		mvc.perform(get(historial).cookie(familia())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].length()").value(8)).andExpect(jsonPath("$[0].temporadaId").value(temporadaId.toString()))
				.andExpect(jsonPath("$[0].anio").value(2026)).andExpect(jsonPath("$[0].temporadaActiva").value(true))
				.andExpect(jsonPath("$[0].categoria").value("Sub 12")).andExpect(jsonPath("$[0].rama").value("Femenino"))
				.andExpect(jsonPath("$[0].divisional").value("A")).andExpect(jsonPath("$[0].numeroCorredor").value("007"))
				.andExpect(jsonPath("$[0].estadoDeportivo").value("SUSPENDIDO"))
				.andExpect(jsonPath("$[0].id").doesNotExist()).andExpect(jsonPath("$[0].escuelaId").doesNotExist())
				.andExpect(jsonPath("$[0].deportistaId").doesNotExist()).andExpect(jsonPath("$[0].activo").doesNotExist())
				.andExpect(jsonPath("$[0].dni").doesNotExist()).andExpect(jsonPath("$[0].creadoEn").doesNotExist());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).historial(actor.capture(), eq(deportistaId));
		assertThat(actor.getValue()).isEqualTo(new UsuarioAutenticado(usuarioId, escuelaId, familiaId, Rol.FAMILIA));
	}

	@Test
	void unHistorialVacioEsUnaListaVacia200() throws Exception {
		when(servicio.historial(any(), eq(deportistaId))).thenReturn(List.of());

		mvc.perform(get(historial).cookie(familia())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void elDetalleDevuelveUnObjetoConLosOchoCampos() throws Exception {
		when(servicio.temporada(any(), eq(deportistaId), eq(temporadaId))).thenReturn(fila());

		mvc.perform(get(detalle).cookie(familia())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(8))
				.andExpect(jsonPath("$.anio").value(2026)).andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.escuelaId").doesNotExist());
	}

	@Test
	void losNotFoundDelServicioSeTraducenConSuCodigo() throws Exception {
		when(servicio.historial(any(), any())).thenThrow(DeportistaAdminService.deportistaNoEncontrado());
		when(servicio.temporada(any(), any(), any())).thenThrow(DeportistaTemporadaAdminService.filaNoEncontrada());

		mvc.perform(get(historial).cookie(familia())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO"));
		mvc.perform(get(detalle).cookie(familia())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("DEPORTISTA_TEMPORADA_NO_ENCONTRADA"));
	}

	@Test
	void losParametrosDeAlcanceDeLaSolicitudSeIgnoran() throws Exception {
		when(servicio.historial(any(), any())).thenReturn(List.of());
		UUID ajena = UUID.randomUUID();

		mvc.perform(get(historial).param("familiaId", ajena.toString()).param("escuelaId", ajena.toString())
				.param("usuarioId", ajena.toString()).cookie(familia())).andExpect(status().isOk());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).historial(actor.capture(), eq(deportistaId));
		assertThat(actor.getValue().familiaId()).isEqualTo(familiaId);
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
	}

	@Test
	void unUuidMalFormadoDa400SolicitudInvalidaSinLlegarAlServicio() throws Exception {
		mvc.perform(get("/api/familia/deportistas/no-es-uuid/temporadas").cookie(familia())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		mvc.perform(get(historial + "/no-es-uuid").cookie(familia())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		verifyNoInteractions(servicio);
	}

	@Test
	void sinSesionDa401YUnAdminDa403SinTocarElServicio() throws Exception {
		for (String ruta : List.of(historial, detalle)) {
			mvc.perform(get(ruta)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
			mvc.perform(get(ruta).cookie(admin())).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void cualquierMetodoQueEscribeDa405ConCsrfY403SinCsrfYNuncaLlegaAlServicio() throws Exception {
		for (String ruta : List.of(historial, detalle)) {
			Map<String, MockHttpServletRequestBuilder> escrituras = Map.of("POST", post(ruta), "PUT", put(ruta), "PATCH",
					patch(ruta), "DELETE", delete(ruta));
			for (var escritura : escrituras.entrySet()) {
				mvc.perform(conCsrf(escritura.getValue()).cookie(familia())).andExpect(status().isMethodNotAllowed())
						.andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
			}
			mvc.perform(post(ruta).cookie(familia())).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		}
		verifyNoInteractions(servicio);
	}
}
