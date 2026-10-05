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
 * {@link BaseOpenApiWebMvc}: cadena de seguridad real + springdoc. Con el perfil {@code prod}: la ruta no existe ({@code springdoc.api-docs.enabled=false}), tampoco para un ADMIN.
 * La interfaz de Swagger no existe en ningun perfil (no esta el starter de UI y {@code springdoc.swagger-ui.enabled=false}).
 */
@ActiveProfiles("prod")
class OpenApiExposicionProdTest extends BaseOpenApiWebMvc {

	private static final String[] RUTAS_DE_ESPECIFICACION = { "/v3/api-docs", "/v3/api-docs.yaml" };
	private static final String[] RUTAS_DE_INTERFAZ = { "/swagger-ui.html", "/swagger-ui/index.html",
			"/v3/api-docs/swagger-config" };


	@Autowired
	Environment entorno;
	@Autowired
	org.springframework.context.ApplicationContext contexto;

	@Test
	void laPropiedadApagaLaEspecificacionYLaInterfazEnProd() {
		assertThat(entorno.getActiveProfiles()).contains("prod").doesNotContain("dev");
		assertThat(entorno.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
		assertThat(entorno.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
		assertThat(contexto.getBeanNamesForType(org.springdoc.webmvc.api.OpenApiWebMvcResource.class)).isEmpty();
	}

	@Test
	void laRutaNoExisteNiParaUnAdminYElAnonimoRecibe401() throws Exception {
		for (String ruta : RUTAS_DE_ESPECIFICACION) {
			mvc.perform(get(ruta).cookie(sesion(Rol.ADMIN))).andExpect(status().isNotFound());
			mvc.perform(get(ruta)).andExpect(status().isUnauthorized());
			mvc.perform(get(ruta).cookie(sesion(Rol.FAMILIA))).andExpect(status().isForbidden());
		}
	}

	@Test
	void laApiSigueFuncionandoEnProd() throws Exception {
		mvc.perform(get("/api/admin/tutores/" + java.util.UUID.randomUUID()).cookie(sesion(Rol.ADMIN))).andExpect(status().isOk());
	}
}
