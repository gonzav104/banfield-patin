package com.banfieldpatin.backend.temporadas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.banfieldpatin.backend.temporadas.dto.TemporadaRespuesta;
import com.banfieldpatin.backend.temporadas.dto.TemporadaSolicitud;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

@WebMvcTest(controllers = TemporadaAdminController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class TemporadaAdminControllerWebMvcTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	TemporadaAdminService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UUID id = UUID.randomUUID();

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

	private MockHttpServletRequestBuilder alta(Cookie sesion, String json) throws Exception {
		return conCsrf(post("/api/admin/temporadas").contentType(MediaType.APPLICATION_JSON).content(json)).cookie(sesion);
	}

	// ---------- alta ----------

	@Test
	void altaDevuelve201ConLocationYLaTemporadaInactivaSinCamposInternos() throws Exception {
		when(servicio.crear(any(), any(), any())).thenReturn(new TemporadaRespuesta(id, 2027, false));

		mvc.perform(alta(admin(), "{\"anio\":2027}")).andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/admin/temporadas/" + id))
				.andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.anio").value(2027))
				.andExpect(jsonPath("$.activa").value(false)).andExpect(jsonPath("$.escuelaId").doesNotExist())
				.andExpect(jsonPath("$.creadoEn").doesNotExist());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		ArgumentCaptor<TemporadaSolicitud> solicitud = ArgumentCaptor.forClass(TemporadaSolicitud.class);
		verify(servicio).crear(actor.capture(), solicitud.capture(), any());
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
		assertThat(actor.getValue().id()).isEqualTo(adminId);
		assertThat(solicitud.getValue().anio()).isEqualTo(2027);
	}

	@Test
	void altaConAnioFueraDeRangoONuloDa400ValidacionSinLlamarAlServicio() throws Exception {
		for (String cuerpo : List.of("{\"anio\":2019}", "{\"anio\":2101}", "{\"anio\":null}", "{}")) {
			mvc.perform(alta(admin(), cuerpo)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("VALIDACION"));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void altaConCuerpoIlegibleOTipoIncorrectoDa400SolicitudInvalida() throws Exception {
		for (String cuerpo : List.of("{\"anio\":\"abc\"}", "{\"anio\":", "[]")) {
			mvc.perform(alta(admin(), cuerpo)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void altaNoAceptaCamposQueDecideElServidor() throws Exception {
		for (String campo : List.of("escuelaId", "activa", "estado", "id")) {
			mvc.perform(alta(admin(), "{\"anio\":2027,\"" + campo + "\":\"x\"}")).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void altaDeUnAnioRepetidoDa409ConElCodigoDelServicio() throws Exception {
		when(servicio.crear(any(), any(), any())).thenThrow(TemporadaAdminService.anioDuplicado());

		mvc.perform(alta(admin(), "{\"anio\":2027}")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("TEMPORADA_ANIO_DUPLICADO"));
	}

	// ---------- lectura ----------

	@Test
	void listadoDevuelveLaPaginaYAcotaLosTamanios() throws Exception {
		when(servicio.listar(any(), any())).thenReturn(new Pagina<>(List.of(new TemporadaRespuesta(id, 2027, true)), 0, 100, 1));

		mvc.perform(get("/api/admin/temporadas").param("size", "1000").param("page", "-3").cookie(admin()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.contenido[0].anio").value(2027))
				.andExpect(jsonPath("$.contenido[0].activa").value(true)).andExpect(jsonPath("$.totalElementos").value(1));

		ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
		verify(servicio).listar(any(), pagina.capture());
		assertThat(pagina.getValue().getPageSize()).isEqualTo(100);
		assertThat(pagina.getValue().getPageNumber()).isZero();
	}

	@Test
	void detalleDevuelve200YUnaAjenaOInexistenteDa404() throws Exception {
		when(servicio.obtener(any(), eq(id))).thenReturn(new TemporadaRespuesta(id, 2027, false));
		UUID otra = UUID.randomUUID();
		when(servicio.obtener(any(), eq(otra))).thenThrow(TemporadaAdminService.temporadaNoEncontrada());

		mvc.perform(get("/api/admin/temporadas/" + id).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()));
		mvc.perform(get("/api/admin/temporadas/" + otra).cookie(admin())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("TEMPORADA_NO_ENCONTRADA"));
	}

	@Test
	void unIdQueNoEsUuidDa400() throws Exception {
		mvc.perform(get("/api/admin/temporadas/no-es-uuid").cookie(admin())).andExpect(status().isBadRequest());
		verifyNoInteractions(servicio);
	}

	// ---------- activacion ----------

	@Test
	void activarDevuelve200ConLaTemporadaActivaYLaEscuelaDelJwt() throws Exception {
		when(servicio.activar(any(), eq(id), any())).thenReturn(new TemporadaRespuesta(id, 2027, true));

		mvc.perform(conCsrf(post("/api/admin/temporadas/" + id + "/activar")).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.activa").value(true));

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).activar(actor.capture(), eq(id), any());
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
	}

	@Test
	void activarUnaAjenaDa404YUnConflictoDeConcurrenciaDa409() throws Exception {
		when(servicio.activar(any(), eq(id), any())).thenThrow(TemporadaAdminService.temporadaNoEncontrada());
		UUID otra = UUID.randomUUID();
		when(servicio.activar(any(), eq(otra), any())).thenThrow(TemporadaAdminService.conflictoConcurrente());

		mvc.perform(conCsrf(post("/api/admin/temporadas/" + id + "/activar")).cookie(admin()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("TEMPORADA_NO_ENCONTRADA"));
		mvc.perform(conCsrf(post("/api/admin/temporadas/" + otra + "/activar")).cookie(admin()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CONFLICTO_CONCURRENCIA"));
	}

	// ---------- autenticacion, autorizacion y CSRF ----------

	@Test
	void sinSesionDa401EnTodasLasRutas() throws Exception {
		mvc.perform(get("/api/admin/temporadas")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/temporadas/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(conCsrf(post("/api/admin/temporadas")).contentType(MediaType.APPLICATION_JSON).content("{\"anio\":2027}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(conCsrf(post("/api/admin/temporadas/" + id + "/activar"))).andExpect(status().isUnauthorized());
		verifyNoInteractions(servicio);
	}

	@Test
	void unaFamiliaRecibe403EnTodasLasRutasYNadaLlegaAlServicio() throws Exception {
		mvc.perform(get("/api/admin/temporadas").cookie(familia())).andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/temporadas/" + id).cookie(familia())).andExpect(status().isForbidden());
		mvc.perform(alta(familia(), "{\"anio\":2027}")).andExpect(status().isForbidden());
		mvc.perform(conCsrf(post("/api/admin/temporadas/" + id + "/activar")).cookie(familia()))
				.andExpect(status().isForbidden());
		verifyNoInteractions(servicio);
	}

	@Test
	void sinTokenCsrfLasEscriturasDan403() throws Exception {
		mvc.perform(post("/api/admin/temporadas").contentType(MediaType.APPLICATION_JSON).content("{\"anio\":2027}")
				.cookie(admin())).andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(post("/api/admin/temporadas/" + id + "/activar").cookie(admin())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		verifyNoInteractions(servicio);
	}

	@Test
	void noExistenReemplazoNiBorradoNiDesactivar() throws Exception {
		mvc.perform(conCsrf(put("/api/admin/temporadas/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"anio\":2027}"))
				.cookie(admin())).andExpect(status().isMethodNotAllowed());
		mvc.perform(conCsrf(delete("/api/admin/temporadas/" + id)).cookie(admin())).andExpect(status().isMethodNotAllowed());
		mvc.perform(conCsrf(post("/api/admin/temporadas/" + id + "/desactivar")).cookie(admin()))
				.andExpect(status().isNotFound());
		verifyNoInteractions(servicio);
	}
}
