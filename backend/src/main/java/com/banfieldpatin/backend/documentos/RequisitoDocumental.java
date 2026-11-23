package com.banfieldpatin.backend.documentos;

/** El estado de un requisito. {@code motivoObservacion} solo viaja cuando el estado es OBSERVADO. */
public record RequisitoDocumental(TipoDocumento tipo, EstadoRequisito estado, String motivoObservacion) {
}
