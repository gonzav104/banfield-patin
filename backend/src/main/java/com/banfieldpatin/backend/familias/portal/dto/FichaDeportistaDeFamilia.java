package com.banfieldpatin.backend.familias.portal.dto;

/**
 * Ficha MINIMA de un deportista propio para su FAMILIA, solo lectura: lista CERRADA de campos. Reune los datos generales y
 * la temporada vigente en una sola respuesta. NO incluye {@code id} (ya esta en la ruta), DNI, CUIL, fecha de nacimiento
 * (solo la {@code edad} calculada en el servidor), nacionalidad, domicilio, telefono, correo, {@code escuelaId} ni
 * {@code familiaId}. {@code activo} es el estado del deportista EN LA ESCUELA; el estado deportivo es el de la temporada.
 * {@code edad} es {@code null} sin fecha de nacimiento fiable y {@code temporadaVigente} es {@code null} cuando no hay datos
 * de la temporada vigente (sin decir por que). Los datos personales no se imprimen nunca (logs, excepciones).
 */
public record FichaDeportistaDeFamilia(String nombre, String apellido, Integer edad, boolean activo,
		TemporadaVigenteDeFicha temporadaVigente) {

	@Override
	public String toString() {
		return "FichaDeportistaDeFamilia[]";
	}
}
