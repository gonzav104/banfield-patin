package com.banfieldpatin.backend.familias.adultos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.familias.adultos.dto.AdultoDeFamilia;
import com.banfieldpatin.backend.familias.adultos.dto.AdultosRespuesta;
import com.banfieldpatin.backend.familias.tutores.verificacion.VerificacionTutoresController;
import com.banfieldpatin.backend.familias.tutores.verificacion.VerificacionTutoresService;
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
 * U21-BIS C2 en la capa web: roles, CSRF, cuerpos cerrados (sin eco), clave de idempotencia y que la escuela, la familia y
 * la cuenta salen del token y nunca del cuerpo.
 */
@WebMvcTest(controllers = { AdultosFamiliaController.class, VerificacionTutoresController.class })
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class AdultosYVerificacionWebMvcTest {

	private static final String ADULTO = "{\"nombre\":\"Marta\",\"apellido\":\"Diaz\"}";

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	AdultosFamiliaService adultos;
	@MockitoBean
	VerificacionTutoresService verificacion;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID usuarioId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();

	@Test
	void laFamiliaCargaConSuTokenYLaClaveDelEncabezadoYLaRespuestaNoSeCachea() throws Exception {
		UUID clave = UUID.randomUUID();
		when(adultos.crear(any(), eq(clave), any(), any())).thenReturn(new AdultoDeFamilia(UUID.randomUUID(), "Marta", "Diaz",
				null, null, null, null, "DECLARADO", null, false, false, false, true, true, "0"));
		mvc.perform(escribir(post("/api/familia/tutores"), familia(), ADULTO).header("Idempotency-Key", clave.toString()))
				.andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.estado").value("DECLARADO"));
		verify(adultos).crear(eq(new UsuarioAutenticado(usuarioId, escuelaId, familiaId, Rol.FAMILIA)), eq(clave), any(), any());

		when(adultos.listar(any())).thenReturn(new AdultosRespuesta(false, 10, 0, java.util.List.of()));
		mvc.perform(get("/api/familia/tutores").cookie(familia())).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.tope").value(10));
	}

	@Test
	void sinClaveConPropiedadesAjenasSinCsrfOComoAdminNoLlegaAlServicio() throws Exception {
		mvc.perform(escribir(post("/api/familia/tutores"), familia(), ADULTO)).andExpect(status().isBadRequest());
		String r = mvc.perform(escribir(post("/api/familia/tutores"), familia(),
				"{\"nombre\":\"Marta\",\"apellido\":\"Diaz\",\"familiaId\":\"secreto-123\"}")
				.header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isBadRequest()).andReturn()
				.getResponse().getContentAsString();
		assertThat(r).doesNotContain("secreto-123");
		mvc.perform(post("/api/familia/tutores").cookie(familia()).header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(ADULTO)).andExpect(status().isForbidden());
		mvc.perform(escribir(post("/api/familia/tutores"), admin(), ADULTO).header("Idempotency-Key", UUID.randomUUID().toString()))
				.andExpect(status().isForbidden());
		mvc.perform(escribir(put("/api/familia/tutores/" + UUID.randomUUID()), familia(), ADULTO)).andExpect(status().isBadRequest());
		mvc.perform(escribir(put("/api/familia/tutores/no-es-uuid"), familia(), ADULTO.replace("}", ",\"versionEsperada\":\"0\"}")))
				.andExpect(status().isBadRequest());
		mvc.perform(escribir(post("/api/familia/tutores"), familia(), "{\"nombre\":\"Marta\",\"apellido\":\"Diaz\",\"dni\":\"abc\"}")
				.header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isBadRequest());
		verifyNoInteractions(adultos);
	}

	@Test
	void laBandejaYLaVerificacionSonSoloDeAdminConCuerposCerrados() throws Exception {
		mvc.perform(get("/api/admin/tutores/por-verificar").cookie(familia())).andExpect(status().isForbidden());
		mvc.perform(escribir(post("/api/admin/tutores/" + UUID.randomUUID() + "/verificacion"), familia(),
				"{\"decision\":\"VERIFICAR\",\"versionEsperada\":\"0\"}")).andExpect(status().isForbidden());
		mvc.perform(escribir(post("/api/admin/tutores/" + UUID.randomUUID() + "/verificacion"), admin(),
				"{\"decision\":\"APROBAR\",\"versionEsperada\":\"0\"}")).andExpect(status().isBadRequest());
		mvc.perform(escribir(post("/api/admin/tutores/" + UUID.randomUUID() + "/verificacion"), admin(),
				"{\"decision\":\"VERIFICAR\",\"versionEsperada\":\"0\",\"verificadoPor\":\"x\"}")).andExpect(status().isBadRequest());
		mvc.perform(escribir(post("/api/admin/tutores/" + UUID.randomUUID() + "/verificacion"), admin(), "{\"decision\":\"VERIFICAR\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(escribir(post("/api/admin/tutores/verificacion-lote"), admin(),
				"{\"tutores\":[{\"tutorId\":\"" + UUID.randomUUID() + "\",\"versionEsperada\":\"0\",\"decision\":\"OBSERVAR\"}]}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/admin/tutores/verificacion-lote").cookie(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"tutores\":[]}")).andExpect(status().isForbidden());
		verifyNoInteractions(verificacion);
	}

	private Cookie familia() {
		return new Cookie("BP_SESION", tokens.emitir(usuarioId, Rol.FAMILIA, escuelaId, familiaId));
	}

	private Cookie admin() {
		return new Cookie("BP_SESION", tokens.emitir(UUID.randomUUID(), Rol.ADMIN, escuelaId, null));
	}

	private MockHttpServletRequestBuilder escribir(MockHttpServletRequestBuilder req, Cookie sesion, String cuerpo) throws Exception {
		Cookie x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return req.cookie(x, sesion).header("X-XSRF-TOKEN", x.getValue()).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
	}
}
