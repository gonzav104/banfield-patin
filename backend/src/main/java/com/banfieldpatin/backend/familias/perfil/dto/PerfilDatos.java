package com.banfieldpatin.backend.familias.perfil.dto;

import java.util.UUID;

/** Perfil de tutor de la propia cuenta. El DNI llega enmascarado: nunca se devuelve completo. */
public record PerfilDatos(UUID tutorId, String nombre, String apellido, String dniEnmascarado, String telefono, String email,
		String parentesco, String estado, boolean identidadEditable, String version) {

	@Override
	public String toString() {
		return "PerfilDatos[***]";
	}
}
