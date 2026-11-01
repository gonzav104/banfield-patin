package com.banfieldpatin.backend.temporadas;

import java.util.UUID;

/** Lectura escalar de una fila de deportista_temporada con el anio de su temporada (una sola consulta, sin N+1). */
public record FilaDeportistaTemporada(UUID id, UUID deportistaId, UUID temporadaId, Short anio, String categoria,
		String rama, String divisional, String numeroCorredor, EstadoDeportivo estadoDeportivo) {
}
