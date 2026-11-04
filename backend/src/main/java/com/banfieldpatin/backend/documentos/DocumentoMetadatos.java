package com.banfieldpatin.backend.documentos;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
/** Proyeccion permitida: nunca serializar una entidad ni metadatos de almacenamiento. */
public record DocumentoMetadatos(UUID id, TipoDocumento tipo, EstadoDocumento estado, UUID temporadaId,
 Instant creadoEn, boolean vigente, LocalDate fechaVencimiento, String motivoObservacion) {}
