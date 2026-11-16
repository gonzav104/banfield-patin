package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** FE-05/U6: la revision del deportista es una cadena y se exige solo donde se modifica un deportista existente. */
class OpenApiVersionDeportistaTest extends BaseOpenApiWebMvc {
    @Test void laRevisionDelDetalleEsUnaCadenaYElListadoNoLaTrae() throws Exception {
        var schemas = arbol().path("components").path("schemas");
        assertThat(schemas.path("DeportistaDetalle").path("properties").path("version").path("type").asText()).isEqualTo("string");
        assertThat(schemas.path("DeportistaResumen").path("properties").has("version")).isFalse();
    }

    @Test void laEdicionYLosCambiosDeEstadoExigenLaRevisionYElAltaNo() throws Exception {
        var schemas = arbol().path("components").path("schemas");

        var edicion = schemas.path("DeportistaActualizacion");
        assertThat(edicion.path("properties").path("versionEsperada").path("type").asText()).isEqualTo("string");
        assertThat(edicion.path("required").toString()).contains("versionEsperada");

        var estado = schemas.path("DeportistaVersionEsperada");
        assertThat(estado.path("properties").path("versionEsperada").path("type").asText()).isEqualTo("string");
        assertThat(estado.path("required").toString()).contains("versionEsperada");

        var alta = schemas.path("DeportistaSolicitud");
        assertThat(alta.path("properties").has("versionEsperada")).isFalse(); // un deportista nuevo no tiene revision que esperar
    }

    @Test void lasTresOperacionesDeEscrituraDeclaranElConflictoDeRevision() throws Exception {
        var rutas = arbol().path("paths");
        for (String ruta : new String[] { "/api/admin/deportistas/{id}", "/api/admin/deportistas/{id}/activar",
                "/api/admin/deportistas/{id}/desactivar" }) {
            var operacion = rutas.path(ruta).has("put") ? rutas.path(ruta).path("put") : rutas.path(ruta).path("post");
            assertThat(operacion.path("responses").has("409")).as(ruta).isTrue();
            assertThat(operacion.path("responses").path("409").path("description").asText()).as(ruta).contains("EDICION_DESACTUALIZADA");
        }
    }
}
