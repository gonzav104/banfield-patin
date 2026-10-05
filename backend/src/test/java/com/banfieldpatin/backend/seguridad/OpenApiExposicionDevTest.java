package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;

import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

/**
 * Politica de exposicion de {@code /v3/api-docs} (RNF-14) y prueba de que OpenAPI no cambia la seguridad. Contexto de
 * {@link BaseOpenApiWebMvc}: cadena de seguridad real + springdoc. Con el perfil {@code dev}: 200 aun anonimo, solo para GET.
 * La interfaz de Swagger no existe en ningun perfil (no esta el starter de UI y {@code springdoc.swagger-ui.enabled=false}).
 */
@ActiveProfiles("dev")
class OpenApiExposicionDevTest extends BaseOpenApiWebMvc {

	private static final String[] RUTAS_DE_ESPECIFICACION = { "/v3/api-docs", "/v3/api-docs.yaml" };
	private static final String[] RUTAS_DE_INTERFAZ = { "/swagger-ui.html", "/swagger-ui/index.html",
			"/v3/api-docs/swagger-config" };


	@Autowired
	Environment entorno;

	@Test
	void anonimoRecibe200() throws Exception {
		assertThat(entorno.getActiveProfiles()).contains("dev");
		for (String ruta : RUTAS_DE_ESPECIFICACION) {
			mvc.perform(get(ruta)).andExpect(status().isOk());
		}
	}

	@Test
	void tambienLaLeeUnaFamilia() throws Exception {
		mvc.perform(get("/v3/api-docs").cookie(sesion(Rol.FAMILIA))).andExpect(status().isOk());
	}

	@Test
	void soloGetEsPublico() throws Exception {
		Cookie xsrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");

		mvc.perform(post("/v3/api-docs").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void lasRutasDeLaApiSiguenProtegidasEnDev() throws Exception {
		mvc.perform(get("/api/admin/familias")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/familias").cookie(sesion(Rol.FAMILIA))).andExpect(status().isForbidden());
		mvc.perform(get("/api/familia/mi-familia")).andExpect(status().isUnauthorized());
	}

	@Test
	void laInterfazDeSwaggerNoExisteTampocoEnDev() throws Exception {
		for (String ruta : RUTAS_DE_INTERFAZ) {
			mvc.perform(get(ruta).cookie(sesion(Rol.ADMIN))).andExpect(status().isNotFound());
		}
	}
}
