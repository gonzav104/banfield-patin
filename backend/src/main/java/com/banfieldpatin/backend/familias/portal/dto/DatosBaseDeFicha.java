package com.banfieldpatin.backend.familias.portal.dto;

import java.time.LocalDate;

/**
 * Proyeccion INTERNA de la consulta de la ficha: nunca se serializa ni sale del servicio. Lleva la fecha de nacimiento SOLO
 * para calcular la edad en el servidor; la respuesta ({@link FichaDeportistaDeFamilia}) no la contiene. Los datos
 * personales no se imprimen nunca (logs, excepciones).
 */
public record DatosBaseDeFicha(String nombre, String apellido, LocalDate fechaNacimiento, boolean activo) {

	@Override
	public String toString() {
		return "DatosBaseDeFicha[]";
	}
}
