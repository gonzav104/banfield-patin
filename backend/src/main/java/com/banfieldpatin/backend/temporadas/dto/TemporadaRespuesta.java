package com.banfieldpatin.backend.temporadas.dto;

import java.util.UUID;

import com.banfieldpatin.backend.temporadas.Temporada;
import com.banfieldpatin.backend.temporadas.TemporadaVista;

/** Una temporada para un ADMIN; sin escuelaId ni marcas de tiempo (las asigna la base). */
public record TemporadaRespuesta(UUID id, int anio, boolean activa) {

	public static TemporadaRespuesta de(Temporada t) {
		return new TemporadaRespuesta(t.getId(), t.getAnio(), t.isActiva());
	}

	public static TemporadaRespuesta de(TemporadaVista v) {
		return new TemporadaRespuesta(v.id(), v.anio(), v.activa());
	}
}
