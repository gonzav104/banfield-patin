package com.banfieldpatin.backend.familias.tutores.dto;

import java.util.UUID;

import com.banfieldpatin.backend.deportistas.EnmascaradoDni;
import com.banfieldpatin.backend.familias.tutores.Tutor;

/**
 * Tutor tal como se lista DENTRO del detalle de una familia (O8): igual que {@link TutorRespuesta} pero con el DNI
 * ENMASCARADO (opcional: {@code null} si el tutor no tiene DNI). El DNI completo solo viaja en el detalle individual del
 * tutor ({@link TutorRespuesta}). Sin usuarioId ni escuelaId.
 */
public record TutorResumen(UUID id, UUID familiaId, String nombre, String apellido, String dniEnmascarado,
		String telefono, String email, String parentesco, boolean activo) {

	public static TutorResumen de(Tutor t) {
		return new TutorResumen(t.getId(), t.getFamiliaId(), t.getNombre(), t.getApellido(),
				EnmascaradoDni.de(t.getDni()), t.getTelefono(), t.getEmail(), t.getParentesco(), t.isActivo());
	}

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "TutorResumen[id=" + id + "]";
	}
}
