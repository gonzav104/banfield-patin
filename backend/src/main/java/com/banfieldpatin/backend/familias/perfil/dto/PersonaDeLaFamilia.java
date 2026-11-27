package com.banfieldpatin.backend.familias.perfil.dto;

import java.util.UUID;

/** Una persona de la familia que todavía no tiene cuenta: lo mínimo para reconocerse («¿Sos esta persona?»). */
public record PersonaDeLaFamilia(UUID tutorId, String nombre, String apellido, String parentesco, String version) {

	@Override
	public String toString() {
		return "PersonaDeLaFamilia[***]";
	}
}
