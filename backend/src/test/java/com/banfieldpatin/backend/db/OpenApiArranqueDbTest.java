package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.ValidadorOpenApi;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compatibilidad real de springdoc 3.1.1 con Spring Boot 4.1.1 en la APLICACION COMPLETA (no un slice): arranque con
 * Flyway V1-V4, JPA {@code validate}, la cadena de seguridad y la revalidacion central REALES, PostgreSQL 17 descartable y
 * un ADMIN real de la base con su cookie {@code BP_SESION} emitida por el {@link ServicioTokens} real. El documento que
 * sirve {@code /v3/api-docs} describe todas las rutas {@code /api/**} que Spring MVC mapea y valida estructuralmente.
 * Perfil {@code e2e}: apaga los controladores sonda de prueba, de modo que el mapeo es el de produccion.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
class OpenApiArranqueDbTest extends BaseDbTest {

	private static final String COOKIE = "BP_SESION";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	RequestMappingHandlerMapping mapeo;
	@Autowired
	Environment entorno;

	private DatosDb datos;
	private UUID escuelaId;
	private UUID adminId;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaId = datos.escuela("openapi-" + UUID.randomUUID());
		adminId = datos.admin(escuelaId, "admin@openapi.example", true);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaId);
	}

	private Set<String> rutasApiMapeadas() {
		Set<String> rutas = new TreeSet<>();
		mapeo.getHandlerMethods().forEach((info, metodo) -> {
			for (String patron : info.getPathPatternsCondition().getPatternValues()) {
				if (patron.startsWith("/api/")) {
					for (RequestMethod verbo : info.getMethodsCondition().getMethods()) {
						rutas.add(verbo + " " + patron);
					}
				}
			}
		});
		return rutas;
	}

	@Test
	void laAplicacionCompletaArrancaConSpringdocYSirveUnaEspecificacionValidaAUnAdminReal() throws Exception {
		Cookie admin = new Cookie(COOKIE, tokens.emitir(adminId, Rol.ADMIN, escuelaId, null));

		String cuerpo = mvc.perform(get("/v3/api-docs").cookie(admin)).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		JsonNode documento = new JsonMapper().readTree(cuerpo);

		assertThat(documento.path("openapi").asString()).startsWith("3.1");
		assertThat(ValidadorOpenApi.problemas(documento)).isEmpty();
		Set<String> enLaEspecificacion = new TreeSet<>();
		documento.path("paths").properties().forEach(ruta -> ruta.getValue().propertyNames()
				.forEach(metodo -> enLaEspecificacion.add(metodo.toUpperCase() + " " + ruta.getKey())));
		assertThat(rutasApiMapeadas()).hasSize(39);
		assertThat(enLaEspecificacion).containsExactlyInAnyOrderElementsOf(rutasApiMapeadas());
	}

	@Test
	void sinPerfilDevNiProdLaRutaExigeUnAdminYLaInterfazNoExiste() throws Exception {
		UUID familiaId = datos.familia(escuelaId, "Familia openapi", true);
		UUID usuarioFamiliaId = datos.usuarioFamilia(escuelaId, familiaId, "familia@openapi.example");
		Cookie familia = new Cookie(COOKIE, tokens.emitir(usuarioFamiliaId, Rol.FAMILIA, escuelaId, familiaId));
		Cookie pendiente = new Cookie(COOKIE, tokens.emitirMfaPendiente(adminId, escuelaId));
		Cookie admin = new Cookie(COOKIE, tokens.emitir(adminId, Rol.ADMIN, escuelaId, null));

		assertThat(entorno.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
		mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
		mvc.perform(get("/v3/api-docs").cookie(pendiente)).andExpect(status().isForbidden());
		mvc.perform(get("/v3/api-docs").cookie(familia)).andExpect(status().isForbidden());
		mvc.perform(get("/swagger-ui/index.html").cookie(admin)).andExpect(status().isNotFound());
		mvc.perform(get("/swagger-ui.html").cookie(admin)).andExpect(status().isNotFound());
	}

	@Test
	void unAdminDesactivadoPierdeElAccesoALaEspecificacionComoEnTodaRuta() throws Exception {
		Cookie admin = new Cookie(COOKIE, tokens.emitir(adminId, Rol.ADMIN, escuelaId, null));
		mvc.perform(get("/v3/api-docs").cookie(admin)).andExpect(status().isOk());

		datos.desactivarUsuario(adminId);

		mvc.perform(get("/v3/api-docs").cookie(admin)).andExpect(status().isUnauthorized());
	}
}
