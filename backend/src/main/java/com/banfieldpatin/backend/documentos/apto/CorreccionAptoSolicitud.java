package com.banfieldpatin.backend.documentos.apto;

import com.banfieldpatin.backend.documentos.revision.RevisionDocumentoSolicitud;
import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Corrección administrativa de los tres datos del apto (siempre los tres: es un reemplazo de los datos, no un parche). La
 * versión esperada es la {@code revision} que ADMIN leyó; si el documento cambió entretanto, la corrección se rechaza.
 */
public record CorreccionAptoSolicitud(
        @NotNull @Schema(type = "string", pattern = "^[0-9]{1,19}$")
        @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada,
        @NotNull @Schema(type = "string", format = "date", description = "Fecha de emisión aaaa-mm-dd; no futura.")
        @JsonDeserialize(using = RevisionDocumentoSolicitud.Cadena.class) String fechaEmision,
        @NotNull @Schema(maxLength = 150)
        @JsonDeserialize(using = RevisionDocumentoSolicitud.Cadena.class) String medicoNombre,
        @NotNull @Schema(maxLength = 50)
        @JsonDeserialize(using = RevisionDocumentoSolicitud.Cadena.class) String medicoMatricula) {

    MetadatosApto.Solicitado datos() {
        return new MetadatosApto.Solicitado(fechaEmision, medicoNombre, medicoMatricula);
    }

    /** Son datos de un menor y de un profesional: nunca en una representación textual ni en un log. */
    @Override
    public String toString() {
        return "CorreccionAptoSolicitud";
    }
}
