package com.banfieldpatin.backend.familias.portal.dto;

import java.util.UUID;

import com.banfieldpatin.backend.familias.responsables.TipoVinculoTutor;

/**
 * Un responsable vigente tal como lo ve su propia FAMILIA: lista cerrada de cuatro campos. Sin DNI, contactos, estado, versión
 * ni identificadores de la vigencia, de la familia o de la escuela. {@code tipo} es {@code null} cuando nadie lo declaró, que es
 * distinto de {@code SIN_DETERMINAR}; es un dato declarado: no acredita representación legal ni concede acceso.
 */
public record ResponsableDeFamilia(UUID tutorId, String nombre, String apellido, TipoVinculoTutor tipo) {

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "ResponsableDeFamilia[tutorId=" + tutorId + "]";
	}
}
