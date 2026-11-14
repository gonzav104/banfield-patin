package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class OpenApiVersionFamiliaTest extends BaseOpenApiWebMvc {
    @Test void revisionesSonCadenasYObligatoriasSoloEnComandosDeModificacion() throws Exception {
        var schemas = arbol().path("components").path("schemas");
        assertThat(schemas.path("FamiliaDetalle").path("properties").path("version").path("type").asText()).isEqualTo("string");
        for (String nombre : new String[] { "FamiliaActualizacion", "FamiliaVersionEsperada" }) {
            var schema = schemas.path(nombre);
            assertThat(schema.path("properties").path("versionEsperada").path("type").asText()).isEqualTo("string");
            assertThat(schema.path("required").toString()).contains("versionEsperada");
        }
        assertThat(schemas.path("FamiliaSolicitud").path("properties").has("versionEsperada")).isFalse();
    }
}
