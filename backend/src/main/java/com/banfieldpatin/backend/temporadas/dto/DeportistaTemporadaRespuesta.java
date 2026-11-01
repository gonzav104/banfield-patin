package com.banfieldpatin.backend.temporadas.dto;

import java.util.UUID;

import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.banfieldpatin.backend.temporadas.FilaDeportistaTemporada;

/** Datos deportivos de un deportista en una temporada para un ADMIN; sin escuelaId, DNI ni marcas de tiempo. */
public record DeportistaTemporadaRespuesta(UUID id, UUID deportistaId, UUID temporadaId, int anio, String categoria,
		String rama, String divisional, String numeroCorredor, EstadoDeportivo estadoDeportivo) {

	public static DeportistaTemporadaRespuesta de(FilaDeportistaTemporada f) {
		return new DeportistaTemporadaRespuesta(f.id(), f.deportistaId(), f.temporadaId(), f.anio(), f.categoria(),
				f.rama(), f.divisional(), f.numeroCorredor(), f.estadoDeportivo());
	}
}
