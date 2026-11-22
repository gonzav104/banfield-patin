package com.banfieldpatin.backend.documentos;

import java.time.Instant;
import java.util.UUID;

/**
 * Una fila de la bandeja administrativa de documentos pendientes de revisión: solo identifica el documento y su titular.
 * {@code familiaId} es el de un tutor (necesario para llegar a su ficha) y es null para un deportista. Nunca lleva el
 * archivo, su nombre, su clave de Storage ni su hash, y la versión de revisión se lee en la ficha, no aquí.
 */
public record DocumentoPendienteAdmin(UUID id, TipoDocumento tipo, Instant creadoEn, TitularDocumento titularTipo,
        UUID titularId, String titularNombre, UUID familiaId, boolean reemplazo) {

    @Override
    public String toString() {
        return "DocumentoPendienteAdmin[id=" + id + "]";
    }
}
