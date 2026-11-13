package com.banfieldpatin.backend.familias.portal.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Fila del listado de deportistas de la FAMILIA. {@code activo} se expone a proposito: un deportista inactivo con vinculo
 * ACTIVO sigue visible (la inactividad deportiva no es una revocacion de acceso). {@code edad} la calcula el servidor con la
 * regla unica {@code EdadDeportista} (la misma de la ficha); es {@code null} sin fecha de nacimiento fiable.
 * {@code fechaNacimiento} se mantiene TEMPORALMENTE solo por compatibilidad con el cliente actual y se retira cuando el
 * cliente lea {@code edad}. Sin escuelaId, esPrincipal, datos del vinculo ni DNI. Se arma a partir de la proyeccion interna
 * {@link DatosListadoDeFamilia} (una sola consulta con el alcance de la familia).
 */
public record DeportistaDeFamilia(UUID id, String nombre, String apellido, LocalDate fechaNacimiento, Integer edad,
		boolean activo) {

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "DeportistaDeFamilia[id=" + id + "]";
	}
}
