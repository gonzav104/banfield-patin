package com.banfieldpatin.backend.familias.dto;

import java.util.UUID;

/** Dato minimo para que un ADMIN elija una familia al invitar. */
public record FamiliaResumen(UUID id, String nombreReferencia) {
}
