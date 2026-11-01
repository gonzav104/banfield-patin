package com.banfieldpatin.backend.temporadas;

import java.util.UUID;

/** Lectura escalar de una fila del plantel de una temporada, con nombre y apellido; nunca el DNI. */
public record FilaPlantel(UUID id, UUID deportistaId, UUID temporadaId, String nombre, String apellido,
		String categoria, String rama, String divisional, String numeroCorredor, EstadoDeportivo estadoDeportivo) {
}
