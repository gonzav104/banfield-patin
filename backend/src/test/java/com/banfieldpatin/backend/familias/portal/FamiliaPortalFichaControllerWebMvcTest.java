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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import com.banfieldpatin.backend.familias.portal.dto.FichaDeportistaDeFamilia;
import com.banfieldpatin.backend.familias.portal.dto.TemporadaVigenteDeFicha;
import com.banfieldpatin.backend.seguridad.CookieBearerTokenResolver;
import com.banfieldpatin.backend.seguridad.CookieSesion;
import com.banfieldpatin.backend.seguridad.JwtConfig;
import com.banfieldpatin.backend.seguridad.ManejadorAccesoDenegadoJson;
import com.banfieldpatin.backend.seguridad.PuntoEntradaJson;
import com.banfieldpatin.backend.seguridad.SeguridadConfig;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.SesionVigenteDePrueba;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

/**
 * La ficha minima de FAMILIA por HTTP con la cadena de seguridad real y el servicio simulado: el contrato JSON es EXACTO (se
 * compara la cadena completa, en orden), los nulos se serializan, solo se lee y la identidad sale de la sesion.
 */
@WebMvcTest(controllers = FamiliaPortalFichaController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class FamiliaPortalFichaControllerWebMvcTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	FamiliaPortalFichaService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final UUID usuarioId = UUID.randomUUID();
	private final UUID deportistaId = UUID.randomUUID();
	private final String ruta = "/api/familia/deportistas/" + deportistaId + "/ficha";

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
	void devuelveExactamenteElContratoAcordadoYNingunDatoMas() throws Exception {
		when(servicio.ficha(any(), eq(deportistaId))).thenReturn(new FichaDeportistaDeFamilia("Ana", "Paz", 9, true,
				new TemporadaVigenteDeFicha((short) 2026, "Sub 12", "Femenino", "A", "007", EstadoDeportivo.SUSPENDIDO)));

		mvc.perform(get(ruta).cookie(familia())).andExpect(status().isOk())
				.andExpect(content().string("{\"nombre\":\"Ana\",\"apellido\":\"Paz\",\"edad\":9,\"activo\":true,"
						+ "\"temporadaVigente\":{\"anio\":2026,\"categoria\":\"Sub 12\",\"rama\":\"Femenino\","
						+ "\"divisional\":\"A\",\"numeroCorredor\":\"007\",\"estadoDeportivo\":\"SUSPENDIDO\"}}"));

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).ficha(actor.capture(), eq(deportistaId));
		assertThat(actor.getValue()).isEqualTo(new UsuarioAutenticado(usuarioId, escuelaId, familiaId, Rol.FAMILIA));
	}

	@Test
	void losNulosSeSerializanComoNulosYNoSeOmiten() throws Exception {
		when(servicio.ficha(any(), eq(deportistaId))).thenReturn(new FichaDeportistaDeFamilia("Ana", "Paz", null, false,
				null));

		mvc.perform(get(ruta).cookie(familia())).andExpect(status().isOk()).andExpect(content()
				.string("{\"nombre\":\"Ana\",\"apellido\":\"Paz\",\"edad\":null,\"activo\":false,\"temporadaVigente\":null}"));
	}

	@Test
	void losTextosDeportivosNulosSeSerializanComoNulos() throws Exception {
		when(servicio.ficha(any(), eq(deportistaId))).thenReturn(new FichaDeportistaDeFamilia("Ana", "Paz", 9, true,
				new TemporadaVigenteDeFicha((short) 2026, null, null, null, null, EstadoDeportivo.ACTIVO)));

		mvc.perform(get(ruta).cookie(familia())).andExpect(status().isOk())
				.andExpect(jsonPath("$.temporadaVigente.length()").value(6))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("\"temporadaVigente\":{\"anio\":2026,"
						+ "\"categoria\":null,\"rama\":null,\"divisional\":null,\"numeroCorredor\":null,"
						+ "\"estadoDeportivo\":\"ACTIVO\"}")));
	}

	@Test
	void ningunCampoSensibleNiInternoApareceEnLaRespuesta() throws Exception {
		when(servicio.ficha(any(), eq(deportistaId))).thenReturn(new FichaDeportistaDeFamilia("Ana", "Paz", 9, true,
				new TemporadaVigenteDeFicha((short) 2026, "Sub 12", "Femenino", "A", "007", EstadoDeportivo.ACTIVO)));

		mvc.perform(get(ruta).cookie(familia())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5))
				.andExpect(jsonPath("$.id").doesNotExist()).andExpect(jsonPath("$.dni").doesNotExist())
				.andExpect(jsonPath("$.cuil").doesNotExist()).andExpect(jsonPath("$.fechaNacimiento").doesNotExist())
				.andExpect(jsonPath("$.nacionalidad").doesNotExist()).andExpect(jsonPath("$.domicilio").doesNotExist())
				.andExpect(jsonPath("$.telefonoContacto").doesNotExist())
				.andExpect(jsonPath("$.emailFederativo").doesNotExist()).andExpect(jsonPath("$.escuelaId").doesNotExist())
				.andExpect(jsonPath("$.familiaId").doesNotExist())
				.andExpect(jsonPath("$.temporadaVigente.temporadaId").doesNotExist())
				.andExpect(jsonPath("$.temporadaVigente.temporadaActiva").doesNotExist())
				.andExpect(jsonPath("$.temporadaVigente.id").doesNotExist());
	}

	@Test
	void elNotFoundDelServicioSeTraduceConSuCodigo() throws Exception {
		when(servicio.ficha(any(), any())).thenThrow(DeportistaAdminService.deportistaNoEncontrado());

		mvc.perform(get(ruta).cookie(familia())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO"));
	}

	@Test
	void losParametrosDeAlcanceDeLaSolicitudSeIgnoran() throws Exception {
		when(servicio.ficha(any(), any())).thenReturn(new FichaDeportistaDeFamilia("Ana", "Paz", 9, true, null));
		UUID ajena = UUID.randomUUID();

		mvc.perform(get(ruta).param("familiaId", ajena.toString()).param("escuelaId", ajena.toString())
				.param("usuarioId", ajena.toString()).cookie(familia())).andExpect(status().isOk());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).ficha(actor.capture(), eq(deportistaId));
		assertThat(actor.getValue().familiaId()).isEqualTo(familiaId);
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
		assertThat(actor.getValue().id()).isEqualTo(usuarioId);
	}

	@Test
	void unUuidMalFormadoDa400SolicitudInvalidaSinLlegarAlServicio() throws Exception {
		mvc.perform(get("/api/familia/deportistas/no-es-uuid/ficha").cookie(familia())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		verifyNoInteractions(servicio);
	}

	@Test
	void sinSesionDa401YUnAdminDa403SinTocarElServicio() throws Exception {
		mvc.perform(get(ruta)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		mvc.perform(get(ruta).cookie(admin())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		verifyNoInteractions(servicio);
	}

	@Test
	void cualquierMetodoQueEscribeDa405ConCsrfY403SinCsrfYNuncaLlegaAlServicio() throws Exception {
		Map<String, MockHttpServletRequestBuilder> escrituras = Map.of("POST", post(ruta), "PUT", put(ruta), "PATCH",
				patch(ruta), "DELETE", delete(ruta));
		for (var escritura : escrituras.entrySet()) {
			mvc.perform(conCsrf(escritura.getValue()).cookie(familia())).andExpect(status().isMethodNotAllowed())
					.andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
		}
		mvc.perform(post(ruta).cookie(familia())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		verifyNoInteractions(servicio);
		assertThat(List.of(ruta)).hasSize(1);
	}
}
