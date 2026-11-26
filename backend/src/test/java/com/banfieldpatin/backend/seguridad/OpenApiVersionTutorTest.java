package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/** FE-05/U4: las revisiones de Tutor son cadenas y se exigen solo donde se modifica un tutor. */
class OpenApiVersionTutorTest extends BaseOpenApiWebMvc {
    @Test void laRevisionDelTutorEsUnaCadenaYLasDelAltaYLaEdicionSonObligatorias() throws Exception {
        var schemas = arbol().path("components").path("schemas");
        assertThat(schemas.path("TutorRespuesta").path("properties").path("version").path("type").asText()).isEqualTo("string");

        var alta = schemas.path("TutorSolicitud");
        assertThat(alta.path("properties").path("versionFamiliaEsperada").path("type").asText()).isEqualTo("string");
        assertThat(alta.path("required").toString()).contains("versionFamiliaEsperada");
        assertThat(alta.path("properties").has("versionEsperada")).isFalse(); // un tutor nuevo no tiene revision que esperar

        var edicion = schemas.path("TutorActualizacion");
        for (String campo : new String[] { "versionEsperada", "versionFamiliaEsperada" }) {
            assertThat(edicion.path("properties").path(campo).path("type").asText()).isEqualTo("string");
            assertThat(edicion.path("required").toString()).contains(campo);
        }
    }

    @Test void comandosDeEstadoUsanSoloLaRevisionDecimalDelTutor() throws Exception {
        var api = arbol();
        var comando = api.path("components").path("schemas").path("TutorVersionEsperada");
        assertThat(comando.path("properties").propertyNames()).containsExactly("versionEsperada");
        assertThat(comando.path("properties").path("versionEsperada").path("type").asText()).isEqualTo("string");
        assertThat(comando.path("required").toString()).contains("versionEsperada");
        for (String operacion : new String[] { "activar", "desactivar" }) {
            assertThat(api.path("paths").path("/api/admin/tutores/{id}/" + operacion).path("post").path("requestBody")
                    .path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/TutorVersionEsperada");
        }
    }

    @Test void elResumenDelTutorEnElDetalleDeFamiliaNoTraeRevision() throws Exception {
        var schemas = arbol().path("components").path("schemas");
        assertThat(schemas.path("TutorResumen").path("properties").has("version")).isFalse();
    }
}
