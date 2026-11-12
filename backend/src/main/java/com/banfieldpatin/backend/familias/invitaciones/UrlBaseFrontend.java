package com.banfieldpatin.backend.familias.invitaciones;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * URL base del frontend con la que se arma {@code enlaceRegistro}. Solo se admite una URL absoluta http(s), sin
 * credenciales, query ni fragmento: el enlace se comparte con familias y no debe poder apuntar a otro esquema ni
 * arrastrar parametros. Vacia significa "sin enlace" (el ADMIN recibe solo el token). En produccion es obligatoria y
 * debe ser https.
 */
final class UrlBaseFrontend {

	static final String PROPIEDAD = "banfield.frontend.url-base";

	private UrlBaseFrontend() {
	}

	/** Devuelve la base sin barra final, o "" si no esta configurada. Falla si la URL no es confiable. */
	static String normalizar(String valor) {
		if (valor == null || valor.isBlank()) {
			return "";
		}
		String limpio = valor.strip();
		URI uri;
		try {
			uri = new URI(limpio);
		} catch (URISyntaxException e) {
			throw invalida();
		}
		String esquema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!(esquema.equals("http") || esquema.equals("https")) || uri.getHost() == null
				|| uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
				|| limpio.contains("#") || limpio.contains("?")) {
			throw invalida();
		}
		return limpio.endsWith("/") ? limpio.substring(0, limpio.length() - 1) : limpio;
	}

	/** Produccion: la base debe existir y ser https (los enlaces viajan por canales externos). */
	static void exigirEnProduccion(String valor) {
		String base = normalizar(valor);
		if (base.isEmpty()) {
			throw new IllegalStateException(PROPIEDAD + " (FRONTEND_URL_BASE) es obligatoria en produccion.");
		}
		if (!base.toLowerCase(Locale.ROOT).startsWith("https://")) {
			throw new IllegalStateException(PROPIEDAD + " debe usar https en produccion.");
		}
	}

	private static IllegalStateException invalida() {
		// El valor no se incluye: es configuracion, pero no hace falta repetirla en logs.
		return new IllegalStateException(
				PROPIEDAD + " invalida: debe ser una URL absoluta http(s) sin credenciales, query ni fragmento.");
	}
}
