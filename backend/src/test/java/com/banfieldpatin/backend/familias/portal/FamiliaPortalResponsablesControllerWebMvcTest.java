package com.banfieldpatin.backend.familias.portal;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import com.banfieldpatin.backend.familias.portal.dto.ResponsableDeFamilia;
import com.banfieldpatin.backend.familias.portal.dto.ResponsablesDeDeportista;
import com.banfieldpatin.backend.familias.responsables.TipoVinculoTutor;
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

/**
 * Los responsables de un deportista propio por HTTP con la cadena de seguridad real y el servicio simulado: el contrato JSON es
 * EXACTO y de cuatro campos, solo se lee, la identidad sale de la sesión y ningún parámetro del cliente la cambia.
 */
@WebMvcTest(controllers = FamiliaPortalResponsablesController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class FamiliaPortalResponsablesControllerWebMvcTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	FamiliaPortalResponsablesService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final UUID usuarioId = UUID.randomUUID();
	private final UUID deportistaId = UUID.randomUUID();
	private final UUID tutorId = UUID.randomUUID();
	private final String ruta = "/api/familia/deportistas/" + deportistaId + "/responsables";

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

	@Test
	void devuelveExactamenteCuatroCamposPorResponsableYLaIdentidadSaleDeLaSesion() throws Exception {
		when(servicio.responsables(any(), eq(deportistaId))).thenReturn(new ResponsablesDeDeportista(
				List.of(new ResponsableDeFamilia(tutorId, "Ana", "Paz", TipoVinculoTutor.MADRE))));

		mvc.perform(get(ruta).cookie(familia())).andExpect(status().isOk())
				.andExpect(content().string("{\"responsables\":[{\"tutorId\":\"" + tutorId
						+ "\",\"nombre\":\"Ana\",\"apellido\":\"Paz\",\"tipo\":\"MADRE\"}]}"))
				.andExpect(jsonPath("$.responsables[0].length()").value(4));

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).responsables(actor.capture(), eq(deportistaId));
		assertThat(actor.getValue()).isEqualTo(new UsuarioAutenticado(usuarioId, escuelaId, familiaId, Rol.FAMILIA));
	}

	@Test
	void elTipoNuloSeSerializaComoNuloYNoSeConfundeConSinDeterminar() throws Exception {
		when(servicio.responsables(any(), eq(deportistaId))).thenReturn(new ResponsablesDeDeportista(List.of(
				new ResponsableDeFamilia(tutorId, "Ana", "Paz", null),
				new ResponsableDeFamilia(UUID.randomUUID(), "Luz", "Gil", TipoVinculoTutor.SIN_DETERMINAR))));

		mvc.perform(get(ruta).cookie(familia())).andExpect(status().isOk())
				.andExpect(jsonPath("$.responsables[0].tipo").value((Object) null))
				.andExpect(jsonPath("$.responsables[0]", org.hamcrest.Matchers.hasKey("tipo")))
				.andExpect(jsonPath("$.responsables[1].tipo").value("SIN_DETERMINAR"));
	}

	@Test
	void laListaVaciaEsUnaRespuestaValida() throws Exception {
		when(servicio.responsables(any(), eq(deportistaId))).thenReturn(new ResponsablesDeDeportista(List.of()));
		mvc.perform(get(ruta).cookie(familia())).andExpect(status().isOk())
				.andExpect(content().string("{\"responsables\":[]}"));
	}

	@Test
	void ningunCampoSensibleNiInternoApareceEnLaRespuesta() throws Exception {
		when(servicio.responsables(any(), eq(deportistaId))).thenReturn(new ResponsablesDeDeportista(
				List.of(new ResponsableDeFamilia(tutorId, "Ana", "Paz", TipoVinculoTutor.PADRE))));

		mvc.perform(get(ruta).cookie(familia())).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$.responsables[0].dni").doesNotExist())
				.andExpect(jsonPath("$.responsables[0].telefono").doesNotExist())
				.andExpect(jsonPath("$.responsables[0].email").doesNotExist())
				.andExpect(jsonPath("$.responsables[0].parentesco").doesNotExist())
				.andExpect(jsonPath("$.responsables[0].version").doesNotExist())
				.andExpect(jsonPath("$.responsables[0].id").doesNotExist())
				.andExpect(jsonPath("$.responsables[0].familiaId").doesNotExist())
				.andExpect(jsonPath("$.responsables[0].escuelaId").doesNotExist())
				.andExpect(jsonPath("$.responsables[0].causaCierre").doesNotExist())
				.andExpect(jsonPath("$.responsables[0].creadoEn").doesNotExist())
				.andExpect(jsonPath("$.historial").doesNotExist());
	}

	@Test
	void losParametrosDelClienteNoCambianElAlcance() throws Exception {
		when(servicio.responsables(any(), eq(deportistaId))).thenReturn(new ResponsablesDeDeportista(List.of()));
		var ajena = UUID.randomUUID();
		mvc.perform(get(ruta).param("familiaId", ajena.toString()).param("escuelaId", ajena.toString())
				.param("historial", "true").cookie(familia())).andExpect(status().isOk());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).responsables(actor.capture(), eq(deportistaId));
		assertThat(actor.getValue().familiaId()).isEqualTo(familiaId);
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
	}

	@Test
	void el404EsUniformeYNoDistingueLaCausa() throws Exception {
		when(servicio.responsables(any(), eq(deportistaId))).thenThrow(DeportistaAdminService.deportistaNoEncontrado());
		mvc.perform(get(ruta).cookie(familia())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO"))
				.andExpect(jsonPath("$.responsables").doesNotExist());
	}

	@Test
	void soloLaFamiliaConSesionPuedeLeerYElAdminNoLlegaAlServicio() throws Exception {
		mvc.perform(get(ruta)).andExpect(status().isUnauthorized());
		mvc.perform(get(ruta).cookie(admin())).andExpect(status().isForbidden());
		verifyNoInteractions(servicio);
	}

	@Test
	void ningunMetodoQueMuteEstaExpuesto() throws Exception {
		mvc.perform(conCsrf(post(ruta)).cookie(familia()).contentType("application/json").content("{}"))
				.andExpect(status().isMethodNotAllowed());
		mvc.perform(conCsrf(put(ruta)).cookie(familia()).contentType("application/json").content("{}"))
				.andExpect(status().isMethodNotAllowed());
		mvc.perform(conCsrf(delete(ruta)).cookie(familia())).andExpect(status().isMethodNotAllowed());
		verifyNoInteractions(servicio);
	}
}
