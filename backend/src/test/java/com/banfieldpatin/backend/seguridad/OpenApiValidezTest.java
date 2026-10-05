package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.banfieldpatin.backend.usuarios.Rol;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * RNF-14 "la especificacion deberá validar sin errores": validacion estructural (sin dependencias nuevas) del documento
 * real que sirve {@code /v3/api-docs} y pruebas del propio validador con documentos rotos, para que un "sin problemas" no
 * sea un validador que nunca falla. Los limites del validador estan en {@link ValidadorOpenApi}.
 */
class OpenApiValidezTest extends BaseOpenApiWebMvc {

	private static final JsonMapper JSON = new JsonMapper();

	@Test
	void laRutaDeLaEspecificacionResponde200ConJsonOpenApiValido() throws Exception {
		mvc.perform(get("/v3/api-docs").cookie(sesion(Rol.ADMIN))).andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

		JsonNode documento = arbol();

		assertThat(documento.path("openapi").asString()).startsWith("3.1");
		assertThat(documento.path("paths").size()).isEqualTo(32);
		assertThat(documento.path("components").path("schemas").size()).isGreaterThan(30);
		assertThat(ValidadorOpenApi.problemas(documento)).isEmpty();
	}

	@Test
	void laVersionYamlTambienSeSirveAUnAdmin() throws Exception {
		mvc.perform(get("/v3/api-docs.yaml").cookie(sesion(Rol.ADMIN))).andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.startsWith("openapi: 3.1")));
	}

	@Test
	void ningunOperationIdSeRepiteYTodosSonEstables() throws Exception {
		List<String> ids = new java.util.ArrayList<>();
		arbol().path("paths").properties().forEach(ruta -> ruta.getValue().properties()
				.forEach(op -> ids.add(op.getValue().path("operationId").asString())));

		assertThat(ids).hasSize(39).doesNotHaveDuplicates().allMatch(id -> id.matches("[A-Za-z]+_[A-Za-z]+"));
	}

	// ---------- el validador detecta cada tipo de defecto (controles negativos) ----------

	private static List<String> problemas(String json) throws Exception {
		return ValidadorOpenApi.problemas(JSON.readTree(json));
	}

	private static final String BASE = """
			{"openapi":"3.1.0","info":{"title":"t","version":"1"},
			 "paths":{"/api/a/{id}":{"get":{"operationId":"A_a",
			   "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"string"}}],
			   "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{"$ref":"#/components/schemas/X"}}}}}}}},
			 "components":{"schemas":{"X":{"type":"object","properties":{"a":{"type":"string"}},"required":["a"]}}}}
			""";

	@Test
	void unDocumentoCorrectoNoTieneProblemas() throws Exception {
		assertThat(problemas(BASE)).isEmpty();
	}

	@Test
	void detectaUnRefQueNoResuelve() throws Exception {
		assertThat(problemas(BASE.replace("schemas/X\"}", "schemas/NoExiste\"}"))).anyMatch(p -> p.contains("$ref que no resuelve"));
	}

	@Test
	void detectaUnaOperacionSinResponses() throws Exception {
		assertThat(problemas(BASE.replace("\"responses\":{\"200\":{\"description\":\"OK\",\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/X\"}}}}}", "\"responses\":{}")))
				.anyMatch(p -> p.contains("'responses' ausente o vacio"));
	}

	@Test
	void detectaUnaRespuestaSinDescripcion() throws Exception {
		assertThat(problemas(BASE.replace("\"description\":\"OK\",", ""))).anyMatch(p -> p.contains("no tiene 'description'"));
	}

	@Test
	void detectaOperationIdRepetido() throws Exception {
		String doble = BASE.replace("\"paths\":{\"/api/a/{id}\":{\"get\":{\"operationId\":\"A_a\",",
				"\"paths\":{\"/api/b/{id}\":{\"get\":{\"operationId\":\"A_a\","
						+ "\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\"}}],"
						+ "\"responses\":{\"200\":{\"description\":\"OK\"}}}},"
						+ "\"/api/a/{id}\":{\"get\":{\"operationId\":\"A_a\",");

		assertThat(problemas(doble)).anyMatch(p -> p.contains("operationId repetido"));
	}

	@Test
	void detectaVersionDeOpenApiFaltanteOInvalida() throws Exception {
		assertThat(problemas(BASE.replace("\"openapi\":\"3.1.0\"", "\"openapi\":\"2.0\""))).anyMatch(p -> p.contains("'openapi'"));
		assertThat(problemas(BASE.replace("\"openapi\":\"3.1.0\",", ""))).anyMatch(p -> p.contains("'openapi'"));
	}

	@Test
	void detectaParametroDeRutaSinDeclararOUnRequiredInexistente() throws Exception {
		assertThat(problemas(BASE.replace("\"required\":true,", ""))).anyMatch(p -> p.contains("required=true"));
		assertThat(problemas(BASE.replace("\"in\":\"path\"", "\"in\":\"query\""))).anyMatch(p -> p.contains("plantilla"));
		assertThat(problemas(BASE.replace("\"required\":[\"a\"]", "\"required\":[\"b\"]"))).anyMatch(p -> p.contains("'required' nombra 'b'"));
	}

	@Test
	void detectaUnRequisitoDeSeguridadNoDeclarado() throws Exception {
		assertThat(problemas(BASE.replace("\"operationId\":\"A_a\",", "\"operationId\":\"A_a\",\"security\":[{\"fantasma\":[]}],")))
				.anyMatch(p -> p.contains("requisito de seguridad 'fantasma'"));
	}

	@Test
	void detectaRutasVaciasOQueNoEmpiezanConBarra() throws Exception {
		assertThat(problemas("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},\"paths\":{}}"))
				.anyMatch(p -> p.contains("'paths' ausente o vacio"));
		assertThat(problemas(BASE.replace("\"/api/a/{id}\"", "\"api/a/{id}\""))).anyMatch(p -> p.contains("no empieza con '/'"));
	}
}
