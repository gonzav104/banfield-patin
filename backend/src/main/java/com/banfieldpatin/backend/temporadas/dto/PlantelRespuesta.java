package com.banfieldpatin.backend.temporadas.dto;

import java.util.UUID;

import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.banfieldpatin.backend.temporadas.FilaPlantel;

/** Un integrante del plantel de una temporada: nombre y apellido, nunca el DNI (el enmascarado es la unidad temp-4). */
public record PlantelRespuesta(UUID id, UUID deportistaId, UUID temporadaId, String nombre, String apellido,
		String categoria, String rama, String divisional, String numeroCorredor, EstadoDeportivo estadoDeportivo) {

	public static PlantelRespuesta de(FilaPlantel f) {
		return new PlantelRespuesta(f.id(), f.deportistaId(), f.temporadaId(), f.nombre(), f.apellido(), f.categoria(),
				f.rama(), f.divisional(), f.numeroCorredor(), f.estadoDeportivo());
	}
}
