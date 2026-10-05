package com.banfieldpatin.backend.familias.portal.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Fila del listado de deportistas de la FAMILIA. {@code activo} se expone a proposito: un deportista inactivo con vinculo
 * ACTIVO sigue visible (la inactividad deportiva no es una revocacion de acceso). Sin escuelaId, esPrincipal, datos del
 * vinculo ni DNI. Se construye con {@code select new} (una sola consulta con el alcance de la familia).
 */
public record DeportistaDeFamilia(UUID id, String nombre, String apellido, LocalDate fechaNacimiento, boolean activo) {

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "DeportistaDeFamilia[id=" + id + "]";
	}
}
