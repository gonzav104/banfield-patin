package com.banfieldpatin.backend.familias.portal.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Detalle de un deportista para su propia FAMILIA: datos permanentes (incluidos DNI y CUIL, que son de su hijo/a) y
 * {@code activo}. Sin escuelaId, esPrincipal, datos del vinculo, marcas de tiempo ni datos de temporada. Se construye con
 * {@code select new} (una sola consulta con el alcance de la familia).
 */
public record DeportistaDeFamiliaDetalle(UUID id, String nombre, String apellido, String dni, String cuil,
		LocalDate fechaNacimiento, String nacionalidad, String domicilio, String otrosDatosDomicilio, String localidad,
		String partido, String codigoPostal, String telefonoContacto, String emailFederativo, boolean activo) {

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "DeportistaDeFamiliaDetalle[id=" + id + "]";
	}
}
