package com.banfieldpatin.backend.db;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Salvaguarda de las pruebas con etiqueta "db". La base es un contenedor Testcontainers local y descartable; aun asi
 * se exige que el host sea loopback y que el nombre de la base contenga "test", para que ninguna configuracion
 * (por ejemplo un DOCKER_HOST remoto) pueda apuntar estas pruebas a una base compartida como Supabase. Nunca se
 * incluye la URL completa en los mensajes (podria llevar credenciales en la query).
 */
final class DestinoDbPrueba {

	private static final Set<String> HOSTS_LOCALES = Set.of("localhost", "127.0.0.1", "[::1]");
	private static final String PREFIJO = "jdbc:postgresql://";

	private DestinoDbPrueba() {
	}

	static void validarUrl(String url) {
		if (url == null || !url.startsWith(PREFIJO)) {
			throw new IllegalStateException("La URL de pruebas db debe tener el formato jdbc:postgresql://localhost:5432/<base_test>");
		}
		URI uri;
		try {
			uri = URI.create(url.substring("jdbc:".length()));
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("La URL de pruebas db no es valida");
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
