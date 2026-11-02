package com.banfieldpatin.backend.familias.dto;

import java.util.List;
import java.util.UUID;

import com.banfieldpatin.backend.familias.Familia;
import com.banfieldpatin.backend.familias.tutores.dto.TutorResumen;

/** Detalle de una familia para un ADMIN; sin escuelaId. */
public record FamiliaDetalle(UUID id, String nombreReferencia, boolean activa, List<TutorResumen> tutores) {

	public FamiliaDetalle {
		tutores = List.copyOf(tutores);
	}

	public static FamiliaDetalle de(Familia f, List<TutorResumen> tutores) {
		return new FamiliaDetalle(f.getId(), f.getNombreReferencia(), f.isActiva(), tutores);
	}
}
