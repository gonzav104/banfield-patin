package com.banfieldpatin.backend.compartido.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ControladorSondaErrores.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ManejadorGlobalErrores.class)
@ActiveProfiles("test")
class ManejadorGlobalErroresTest {

	@Autowired
	MockMvc mvc;

	@Test
	void validacionListaDetallesSinEcoDeValores() throws Exception {
		mvc.perform(post("/sonda/validar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"nombre\":\"\",\"password\":\"corta\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"))
				.andExpect(jsonPath("$.detalles.length()").value(2))
				.andExpect(jsonPath("$.detalles[?(@.campo=='nombre')]").exists())
				.andExpect(jsonPath("$.detalles[?(@.campo=='password')]").exists())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("corta"))));
	}

	@Test
	void excepcionInesperadaDevuelve500GenericoSinFugas() throws Exception {
		mvc.perform(get("/sonda/explota"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.codigo").value("ERROR_INTERNO"))
				.andExpect(jsonPath("$.detalles").isArray())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secreto"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("IllegalState"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("jdbc"))));
	}

	@Test
	void jsonMalformadoDevuelve400Uniforme() throws Exception {
		mvc.perform(post("/sonda/validar").contentType(MediaType.APPLICATION_JSON).content("{no es json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
				.andExpect(jsonPath("$.detalles").isArray());
	}

	@Test
	void metodoNoPermitidoYTipoNoSoportadoMantienenFormato() throws Exception {
		mvc.perform(get("/sonda/validar"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
		mvc.perform(post("/sonda/validar").contentType(MediaType.TEXT_PLAIN).content("x"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.codigo").value("TIPO_MEDIO_NO_SOPORTADO"));
	}

	@Test
	void excepcionNegocioUsaSuEstadoYCodigo() throws Exception {
		mvc.perform(get("/sonda/negocio"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("CODIGO_X"));
	}

	@Test
	void rutaInexistenteDevuelve404Uniforme() throws Exception {
		mvc.perform(get("/no/existe"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("RECURSO_NO_ENCONTRADO"));
	}
}
