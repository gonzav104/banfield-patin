package com.banfieldpatin.backend.documentos.almacen;

import java.util.UUID;

/** Referencia interna persistible, no DTO, URL ni capacidad de borrado. */
public final class DestinoPreparado {
    private final String bucket;
    private final UUID objetoId;

    DestinoPreparado(String bucket, UUID objetoId) {
        if (bucket == null || !bucket.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,62}") || objetoId == null)
            throw new FalloAlmacenDocumentos(FalloAlmacenDocumentos.Codigo.ENTRADA_INVALIDA, false);
        this.bucket = bucket;
        this.objetoId = objetoId;
    }

    /** Sólo recibe datos del registro interno; no aceptar valores procedentes de HTTP. */
    public static DestinoPreparado desdeRegistro(String storageBucket, UUID objetoId) {
        return new DestinoPreparado(storageBucket, objetoId);
    }

    public String bucketInterno() { return bucket; }
    public UUID objetoIdInterno() { return objetoId; }
    @Override public String toString() { return "DestinoPreparado[privado]"; }
}
