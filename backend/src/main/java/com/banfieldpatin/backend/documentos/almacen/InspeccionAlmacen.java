package com.banfieldpatin.backend.documentos.almacen;

import java.util.Optional;
import java.util.OptionalLong;

/** Una observación, nunca prueba de fin de un escritor pendiente ni de integridad SHA-256. */
public final class InspeccionAlmacen {
    public enum Estado { EXISTENCIA_CONFIRMADA, AUSENCIA_OBSERVADA, INCIERTA }
    private final Estado estado;
    private final Long tamanio;
    private final String mime;
    private final FalloAlmacenDocumentos.Codigo motivo;

    private InspeccionAlmacen(Estado estado, Long tamanio, String mime, FalloAlmacenDocumentos.Codigo motivo) {
        this.estado = estado; this.tamanio = tamanio; this.mime = mime; this.motivo = motivo;
    }
    static InspeccionAlmacen existe(long tamanio, String mime) {
        return new InspeccionAlmacen(Estado.EXISTENCIA_CONFIRMADA, tamanio, mime, null);
    }
    static InspeccionAlmacen ausente() {
        return new InspeccionAlmacen(Estado.AUSENCIA_OBSERVADA, null, null, null);
    }
    static InspeccionAlmacen incierta(FalloAlmacenDocumentos.Codigo motivo) {
        return new InspeccionAlmacen(Estado.INCIERTA, null, null, motivo);
    }
    public Estado estado() { return estado; }
    public OptionalLong tamanioBytes() { return tamanio == null ? OptionalLong.empty() : OptionalLong.of(tamanio); }
    public Optional<String> tipoContenido() { return Optional.ofNullable(mime); }
    public Optional<FalloAlmacenDocumentos.Codigo> motivo() { return Optional.ofNullable(motivo); }
    @Override public String toString() { return "InspeccionAlmacen[" + estado + "]"; }
}
