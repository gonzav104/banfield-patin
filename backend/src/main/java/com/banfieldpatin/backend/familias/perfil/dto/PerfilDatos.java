package com.banfieldpatin.backend.familias.perfil.dto;

import java.util.UUID;

/**
 * Perfil de tutor de la propia cuenta. El DNI llega enmascarado: nunca se devuelve completo. {@code estado} es el de los DATOS del
 * tutor (DECLARADO/VERIFICADO/OBSERVADO); {@code cuentaConfirmada} dice si el club confirmó que ESTA cuenta es esa persona (invitación
 * dirigida de ADMIN). Reclamar «soy yo» deja los datos como estaban y la cuenta sin confirmar.
 */
public record PerfilDatos(UUID tutorId, String nombre, String apellido, String dniEnmascarado, String telefono, String email,
		String parentesco, String estado, boolean cuentaConfirmada, boolean identidadEditable, String version) {

	@Override
	public String toString() {
		return "PerfilDatos[***]";
	}
}
