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
 * {@link BaseOpenApiWebMvc}: cadena de seguridad real + springdoc. Sin perfil {@code dev} (cualquier entorno que no sea una maquina de desarrollo): 401 anonimo, 403 FAMILIA, 403 ADMIN
 * con MFA pendiente, 200 ADMIN con sesion completa, misma revalidacion central de la sesion que el resto.
 * La interfaz de Swagger no existe en ningun perfil (no esta el starter de UI y {@code springdoc.swagger-ui.enabled=false}).
 */
class OpenApiExposicionSinDevTest extends BaseOpenApiWebMvc {

	private static final String[] RUTAS_DE_ESPECIFICACION = { "/v3/api-docs", "/v3/api-docs.yaml" };
	private static final String[] RUTAS_DE_INTERFAZ = { "/swagger-ui.html", "/swagger-ui/index.html",
			"/v3/api-docs/swagger-config" };

	@BeforeEach
	void reiniciar() {
		verificador.reiniciar();
	}

	@Test
	void anonimoRecibe401ConElErrorUniforme() throws Exception {
		for (String ruta : RUTAS_DE_ESPECIFICACION) {
			mvc.perform(get(ruta)).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		}
	}

	@Test
	void familiaRecibe403() throws Exception {
		for (String ruta : RUTAS_DE_ESPECIFICACION) {
			mvc.perform(get(ruta).cookie(sesion(Rol.FAMILIA))).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		}
	}

	@Test
	void adminConMfaPendienteRecibe403ComoEnTodaRutaDeAdmin() throws Exception {
		for (String ruta : RUTAS_DE_ESPECIFICACION) {
			mvc.perform(get(ruta).cookie(sesionMfaPendiente())).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		}
		mvc.perform(get("/api/admin/familias").cookie(sesionMfaPendiente())).andExpect(status().isForbidden());
	}

	@Test
	void adminConSesionCompletaRecibe200() throws Exception {
		mvc.perform(get("/v3/api-docs").cookie(sesion(Rol.ADMIN))).andExpect(status().isOk())
				.andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.1")));
		mvc.perform(get("/v3/api-docs.yaml").cookie(sesion(Rol.ADMIN))).andExpect(status().isOk());
	}

	@Test
	void laSesionPasaPorLaMismaRevalidacionCentralQueElRestoDeRutas() throws Exception {
		verificador.rechazar();

		MockHttpServletResponse respuesta = mvc.perform(get("/v3/api-docs").cookie(sesion(Rol.ADMIN)))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
				.andExpect(cookie().maxAge(COOKIE, 0)).andReturn().getResponse();

		assertThat(respuesta.getCookie(COOKIE).getValue()).isEmpty();
		assertThat(verificador.llamadas()).isEqualTo(1);
	}

	@Test
	void siLaBaseNoResponderLaRutaDa503ComoEnElRestoYNoBorraLaCookie() throws Exception {
		verificador.fallarConBaseNoDisponible();

		MockHttpServletResponse respuesta = mvc.perform(get("/v3/api-docs").cookie(sesion(Rol.ADMIN)))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("SERVICIO_NO_DISPONIBLE"))
				.andReturn().getResponse();

		assertThat(respuesta.getCookie(COOKIE)).isNull();
	}

	@Test
	void lasRutasAnonimasNoConsultanLaSesion() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());

		assertThat(verificador.llamadas()).isZero();
	}

	@Test
	void elMetodoDistintoDeGetNoQuedaAbiertoNiSinCsrfNiConCsrf() throws Exception {
		mvc.perform(post("/v3/api-docs").cookie(sesion(Rol.FAMILIA))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		Cookie xsrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		mvc.perform(post("/v3/api-docs").cookie(xsrf, sesion(Rol.FAMILIA)).header("X-XSRF-TOKEN", xsrf.getValue()))
				.andExpect(status().isForbidden());
	}

	@Test
	void laInterfazDeSwaggerNoExisteNiParaUnAdmin() throws Exception {
		for (String ruta : RUTAS_DE_INTERFAZ) {
			mvc.perform(get(ruta).cookie(sesion(Rol.ADMIN))).andExpect(status().isNotFound());
		}
	}

	@Test
	void laSeguridadDelRestoDeLaApiNoCambiaConSpringdocCargado() throws Exception {
		mvc.perform(get("/api/admin/familias")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/familias").cookie(sesion(Rol.FAMILIA))).andExpect(status().isForbidden());
		mvc.perform(get("/api/familia/mi-familia").cookie(sesion(Rol.ADMIN))).andExpect(status().isForbidden());
		mvc.perform(post("/api/admin/familias").cookie(sesion(Rol.ADMIN))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(get("/api/admin/familias").cookie(sesionMfaPendiente())).andExpect(status().isForbidden());
	}
}
