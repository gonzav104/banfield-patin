package com.banfieldpatin.backend.familias.portal.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Proyeccion INTERNA de la consulta del listado de FAMILIA: nunca se serializa ni sale del servicio. Lleva la fecha de
 * nacimiento SOLO para que el servicio calcule la edad; la respuesta ({@link DeportistaDeFamilia}) no la contiene. Los datos
 * personales no se imprimen nunca (logs, excepciones).
 */
public record DatosListadoDeFamilia(UUID id, String nombre, String apellido, LocalDate fechaNacimiento, boolean activo) {

	@Override
	public String toString() {
		return "DatosListadoDeFamilia[id=" + id + "]";
	}
}
