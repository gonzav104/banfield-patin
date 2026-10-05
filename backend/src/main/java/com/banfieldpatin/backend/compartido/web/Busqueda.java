package com.banfieldpatin.backend.compartido.web;

/** Normalizacion del texto de busqueda de los listados administrativos. */
public final class Busqueda {

	public static final int LONGITUD_MAXIMA = 100;

	private Busqueda() {
	}

	/**
	 * Recorta, acota a {@link #LONGITUD_MAXIMA} y escapa los comodines de LIKE con '!' para que la busqueda sea siempre
	 * "contiene" literal (las consultas usan {@code escape '!'}). Null o en blanco = "" (sin filtro).
	 */
	public static String patronLike(String busqueda) {
		if (busqueda == null) {
			return "";
		}
		String limpio = busqueda.strip();
		if (limpio.length() > LONGITUD_MAXIMA) {
			limpio = limpio.substring(0, LONGITUD_MAXIMA);
		}
		return limpio.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

	/**
	 * Si la busqueda, sin puntos ni espacios, son solo digitos, devuelve esos digitos (para buscar por prefijo de DNI);
	 * en cualquier otro caso "".
	 */
	public static String digitos(String busqueda) {
		if (busqueda == null) {
			return "";
		}
		String sinSeparadores = busqueda.replace(".", "").replaceAll("\\s", "");
		return sinSeparadores.matches("\\d+") ? sinSeparadores : "";
	}
}
