package com.banfieldpatin.backend.familias.portal.dto;

import java.util.UUID;

import com.banfieldpatin.backend.familias.tutores.Tutor;

/** Tutor tal como lo ve su propia FAMILIA: sin DNI, sin estado, sin escuelaId, sin familiaId ni usuarioId. */
public record TutorDeFamilia(UUID id, String nombre, String apellido, String parentesco, String telefono,
		String email) {

	public static TutorDeFamilia de(Tutor t) {
		return new TutorDeFamilia(t.getId(), t.getNombre(), t.getApellido(), t.getParentesco(), t.getTelefono(),
				t.getEmail());
	}

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "TutorDeFamilia[id=" + id + "]";
	}
}
