package com.banfieldpatin.backend.familias.portal.dto;

import java.util.List;
import java.util.UUID;

import com.banfieldpatin.backend.familias.Familia;

/** La familia del usuario autenticado y sus tutores activos. Sin escuelaId ni estado (una familia inactiva no entra). */
public record MiFamiliaRespuesta(UUID id, String nombreReferencia, List<TutorDeFamilia> tutores) {

	public MiFamiliaRespuesta {
		tutores = List.copyOf(tutores);
	}

	public static MiFamiliaRespuesta de(Familia f, List<TutorDeFamilia> tutores) {
		return new MiFamiliaRespuesta(f.getId(), f.getNombreReferencia(), tutores);
	}

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "MiFamiliaRespuesta[id=" + id + "]";
	}
}
