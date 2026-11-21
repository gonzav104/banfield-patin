package com.banfieldpatin.backend.documentos;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Metadatos de un documento del deportista o del tutor (U17, U19) para ADMIN; la versión decimal conserva toda la precisión de BIGINT. */
public record DocumentoAdminMetadatos(UUID id, TipoDocumento tipo, EstadoDocumento estado, UUID temporadaId,
        Instant creadoEn, boolean vigente, LocalDate fechaVencimiento, String motivoObservacion, String version) {

    public DocumentoAdminMetadatos(UUID id, TipoDocumento tipo, EstadoDocumento estado, UUID temporadaId,
            Instant creadoEn, boolean vigente, LocalDate fechaVencimiento, String motivoObservacion, long version) {
        this(id, tipo, estado, temporadaId, creadoEn, vigente, fechaVencimiento, motivoObservacion,
                Long.toString(version));
    }

    DocumentoMetadatos sinVersion() {
        return new DocumentoMetadatos(id, tipo, estado, temporadaId, creadoEn, vigente, fechaVencimiento,
                motivoObservacion);
    }

    @Override
    public String toString() {
        return "DocumentoAdminMetadatos[id=" + id + "]";
    }
}
