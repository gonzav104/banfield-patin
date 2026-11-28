package com.banfieldpatin.backend.familias.tutores.verificacion.dto;

import java.util.UUID;

/** Estado del tutor después de la decisión y su nueva revisión (cadena decimal). */
public record VerificacionResultado(UUID tutorId, String estado, String motivoObservacion, String version) {
}
