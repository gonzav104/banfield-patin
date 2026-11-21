package com.banfieldpatin.backend.documentos.revision;

import org.springframework.http.HttpStatus;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.EstadoDocumento;
import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record RevisionDocumentoSolicitud(
        @NotNull @Schema(allowableValues = {"APROBADO", "OBSERVADO"})
        @JsonDeserialize(using = Cadena.class) String estado,
        @NotNull @Schema(type = "string", pattern = "^[0-9]{1,19}$")
        @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada,
        @Schema(maxLength = 500, description = "Obligatorio al observar; nulo o ausente al aprobar.")
        @JsonDeserialize(using = Cadena.class) String motivoObservacion) {

    EstadoDocumento destino() {
        return switch (estado) {
            case "APROBADO" -> EstadoDocumento.APROBADO;
            case "OBSERVADO" -> EstadoDocumento.OBSERVADO;
            default -> throw invalida("El estado debe ser APROBADO u OBSERVADO.");
        };
    }

    String motivoPara(EstadoDocumento destino) {
        if (destino == EstadoDocumento.APROBADO) {
            if (motivoObservacion != null) {
                throw invalida("La aprobación no admite un motivo de observación.");
            }
            return null;
        }
        if (motivoObservacion == null) {
            throw invalida("Ingresá un motivo de observación.");
        }
        String motivo = recortar(motivoObservacion);
        if (motivo.isEmpty() || motivo.codePointCount(0, motivo.length()) > 500
                || motivo.indexOf('\0') >= 0 || motivo.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) {
            throw invalida("El motivo de observación debe ser texto válido de 1 a 500 caracteres.");
        }
        return motivo;
    }

    private static String recortar(String texto) {
        int inicio = 0;
        int fin = texto.length();
        while (inicio < fin && espacio(texto.codePointAt(inicio))) {
            inicio += Character.charCount(texto.codePointAt(inicio));
        }
        while (fin > inicio && espacio(texto.codePointBefore(fin))) {
            fin -= Character.charCount(texto.codePointBefore(fin));
        }
        return texto.substring(inicio, fin);
    }

    private static boolean espacio(int codigo) {
        return Character.isWhitespace(codigo) || Character.isSpaceChar(codigo) || codigo == 0x85;
    }

    private static ExcepcionNegocio invalida(String mensaje) {
        return new ExcepcionNegocio(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", mensaje);
    }

    @Override
    public String toString() {
        return "RevisionDocumentoSolicitud";
    }

    public static class Cadena extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext contexto) {
            return parser.currentToken() == JsonToken.VALUE_STRING
                    ? parser.getString() : (String) contexto.handleUnexpectedToken(String.class, parser);
        }
    }
}
