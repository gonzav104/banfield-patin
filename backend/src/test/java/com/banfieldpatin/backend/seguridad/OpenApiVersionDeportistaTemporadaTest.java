package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * FE-05/U9: la revision de la fila de datos deportivos es una cadena decimal: la traen las respuestas de la fila, la exige la
 * edicion y no el alta (una fila nueva no tiene revision que esperar); el plantel no la trae (se recupera del detalle).
 */
class OpenApiVersionDeportistaTemporadaTest extends BaseOpenApiWebMvc {

	@Test
	void laRespuestaDeLaFilaTraeLaRevisionComoCadenaYElPlantelNoLaTrae() throws Exception {
		var schemas = arbol().path("components").path("schemas");

		assertThat(schemas.path("DeportistaTemporadaRespuesta").path("properties").path("version").path("type").asText())
				.isEqualTo("string");
		assertThat(schemas.path("PlantelRespuesta").path("properties").has("version")).isFalse();
	}

	@Test
	void laEdicionExigeLaRevisionDecimalYElAltaNoLaAceptaNiLaExige() throws Exception {
		var schemas = arbol().path("components").path("schemas");

		var edicion = schemas.path("DeportistaTemporadaActualizacion");
		var version = edicion.path("properties").path("versionEsperada");
		assertThat(version.path("type").asText()).isEqualTo("string");
		assertThat(version.path("pattern").asText()).isEqualTo("[0-9]{1,19}");
		assertThat(edicion.path("required").toString()).contains("versionEsperada").contains("estadoDeportivo");

		var alta = schemas.path("DeportistaTemporadaSolicitud");
		assertThat(alta.path("properties").has("versionEsperada")).isFalse();
		assertThat(alta.path("required").toString()).doesNotContain("versionEsperada");
	}

	@Test
	void soloLaEdicionDeclaraElConflictoDeRevisionYLaConsultaYElAltaConservanSusRespuestas() throws Exception {
		var rutas = arbol().path("paths");
		var fila = rutas.path("/api/admin/deportistas/{deportistaId}/temporadas/{temporadaId}");

		var put = fila.path("put").path("responses");
		assertThat(put.has("409")).isTrue();
		assertThat(put.path("409").path("description").asText()).contains("EDICION_DESACTUALIZADA").contains("CONFLICTO_CONCURRENCIA")
				.contains("NUMERO_CORREDOR_DUPLICADO");

		assertThat(fila.path("get").path("responses").has("409")).as("la consulta no tiene conflictos de revision").isFalse();
		var alta = rutas.path("/api/admin/deportistas/{deportistaId}/temporadas").path("post").path("responses");
		assertThat(alta.path("409").path("description").asText()).doesNotContain("EDICION_DESACTUALIZADA");
	}
}
