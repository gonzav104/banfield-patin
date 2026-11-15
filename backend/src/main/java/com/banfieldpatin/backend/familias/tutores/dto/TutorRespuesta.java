package com.banfieldpatin.backend.familias.tutores.dto;

import java.util.UUID;

import com.banfieldpatin.backend.familias.tutores.Tutor;

/**
 * Tutor tal como lo ve un ADMIN. Sin usuarioId ni escuelaId. {@code version} es la revision de escritura del tutor
 * (cadena decimal): la que se devuelve como {@code versionEsperada} al editarlo.
 */
public record TutorRespuesta(UUID id, UUID familiaId, String nombre, String apellido, String dni, String telefono,
		String email, String parentesco, boolean activo, String version) {

	public static TutorRespuesta de(Tutor t) {
		return new TutorRespuesta(t.getId(), t.getFamiliaId(), t.getNombre(), t.getApellido(), t.getDni(),
				t.getTelefono(), t.getEmail(), t.getParentesco(), t.isActivo(), Long.toString(t.getVersion()));
	}

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "TutorRespuesta[id=" + id + "]";
	}
}
