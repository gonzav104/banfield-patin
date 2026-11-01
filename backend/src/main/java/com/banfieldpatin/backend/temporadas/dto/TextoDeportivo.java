package com.banfieldpatin.backend.temporadas.dto;

/**
 * Normalizacion de los textos deportivos configurables (categoria, rama, divisional, numero de corredor). No hay catalogo
 * oficial: se validan al guardar longitud, espacios y caracteres de control, no su contenido.
 */
final class TextoDeportivo {

	/** Ningun caracter de control (saltos de linea, tabulaciones, NUL, ...) en el valor ya normalizado. */
	static final String SIN_CONTROL = "[^\\p{Cntrl}]*";

	private TextoDeportivo() {
	}

	/** Recorta, colapsa las rachas de espacios internos a uno y pasa lo vacio a null. */
	static String texto(String valor) {
		if (valor == null) {
			return null;
		}
		String limpio = valor.strip().replaceAll("\\s+", " ");
		return limpio.isEmpty() ? null : limpio;
	}

	/** Recorta y pasa lo vacio a null; conserva los espacios internos y los ceros iniciales (texto opaco). */
	static String codigo(String valor) {
		if (valor == null) {
			return null;
		}
		String limpio = valor.strip();
		return limpio.isEmpty() ? null : limpio;
	}
}
