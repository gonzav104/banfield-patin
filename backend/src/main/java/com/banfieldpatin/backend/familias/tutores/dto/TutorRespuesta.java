package com.banfieldpatin.backend.familias.tutores.dto;

import java.util.UUID;

/**
 * Tutor tal como lo ve un ADMIN. Sin usuarioId ni escuelaId. Se declara aqui para tipar {@code FamiliaDetalle.tutores};
 * el alta, la edicion y la lectura de tutores llegan con la entidad Tutor.
 */
public record TutorRespuesta(UUID id, UUID familiaId, String nombre, String apellido, String dni, String telefono,
		String email, String parentesco, boolean activo) {
}
