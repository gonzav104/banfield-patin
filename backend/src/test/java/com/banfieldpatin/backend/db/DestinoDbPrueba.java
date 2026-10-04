package com.banfieldpatin.backend.db;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Destino de las pruebas con etiqueta "db". Solo se aceptan bases PostgreSQL LOCALES y descartables: el host debe ser
 * loopback y el nombre de la base debe contener "test". Nunca se usa la base compartida (Supabase) ni se imprime
 * la URL completa (podria llevar credenciales en la query).
 */
final class DestinoDbPrueba {

	static final String VARIABLE_URL = "DB_TEST_URL";
	static final String VARIABLE_USUARIO = "DB_TEST_USER";
	static final String VARIABLE_CLAVE = "DB_TEST_PASSWORD";

	private static final Set<String> HOSTS_LOCALES = Set.of("localhost", "127.0.0.1", "[::1]");
	private static final String PREFIJO = "jdbc:postgresql://";

	record Destino(String url, String usuario, String clave) {
	}

	private DestinoDbPrueba() {
	}

	/** Lee las variables de entorno indicadas y valida el destino; falla con un mensaje claro si falta o no es local. */
	static Destino desdeEntorno(Function<String, String> entorno) {
		String url = entorno.apply(VARIABLE_URL);
		String usuario = entorno.apply(VARIABLE_USUARIO);
		if (url == null || url.isBlank() || usuario == null || usuario.isBlank()) {
			throw new IllegalStateException("Las pruebas db requieren " + VARIABLE_URL + " y " + VARIABLE_USUARIO
					+ " (y opcionalmente " + VARIABLE_CLAVE + ") apuntando a una PostgreSQL LOCAL descartable.");
		}
		validarUrl(url);
		String clave = entorno.apply(VARIABLE_CLAVE);
		return new Destino(url, usuario, clave == null ? "" : clave);
	}

	static void validarUrl(String url) {
		if (url == null || !url.startsWith(PREFIJO)) {
			throw new IllegalStateException(VARIABLE_URL + " debe tener el formato jdbc:postgresql://localhost:5432/<base_test>");
		}
		URI uri;
		try {
			uri = URI.create(url.substring("jdbc:".length()));
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(VARIABLE_URL + " no es una URL valida");
		}
		String host = uri.getHost();
		if (host == null || !HOSTS_LOCALES.contains(host.toLowerCase(Locale.ROOT))) {
			throw new IllegalStateException("Las pruebas db solo corren contra una base LOCAL (localhost, 127.0.0.1 o ::1); "
					+ "host rechazado: " + (host == null ? "(ninguno)" : host));
		}
		String base = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
		if (!base.contains("test")) {
			throw new IllegalStateException("El nombre de la base descartable debe contener 'test' para evitar usar una base real");
		}
	}
}
