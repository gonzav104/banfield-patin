package com.banfieldpatin.backend.familias.portal.dto;

import java.util.List;

/** Envoltorio de la lectura familiar de responsables: vacío es una respuesta válida (el deportista es propio pero no tiene vigentes). */
public record ResponsablesDeDeportista(List<ResponsableDeFamilia> responsables) {

	public ResponsablesDeDeportista {
		responsables = List.copyOf(responsables);
	}
}
