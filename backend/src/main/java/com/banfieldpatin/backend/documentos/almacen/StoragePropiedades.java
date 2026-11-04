package com.banfieldpatin.backend.documentos.almacen;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Sólo se construye al habilitar explícitamente Storage; sin toString que incluya secretos. */
@ConfigurationProperties("banfield.documentos.storage")
public final class StoragePropiedades {
	private final URI url;
	private final String bucket;
	private final String credencial;
	private final Duration conexion;
	private final Duration respuesta;

	public StoragePropiedades(String url, String bucket, String credencial,
			@DefaultValue("PT3S") Duration conexion, @DefaultValue("PT15S") Duration respuesta) {
		URI uri;
		try { uri = URI.create(url == null ? "" : url); }
		catch (IllegalArgumentException ex) { throw invalida(); }
		boolean loopback = "http".equals(uri.getScheme())
				&& ("127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()));
		if (uri.getHost() == null || !("https".equals(uri.getScheme()) || loopback)
				|| uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
				|| !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
				|| uri.getPort() == 0 || uri.getPort() > 65535
				|| bucket == null || !bucket.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}")
				|| credencial == null || credencial.isBlank()
				|| credencial.chars().anyMatch(c -> c <= 32 || c >= 127)
				|| !plazoValido(conexion) || !plazoValido(respuesta)) throw invalida();
		this.url = URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
		this.bucket = bucket;
		this.credencial = credencial;
		this.conexion = conexion;
		this.respuesta = respuesta;
	}

	private static boolean plazoValido(Duration plazo) {
		return plazo != null && plazo.compareTo(Duration.ofMillis(1)) >= 0
				&& plazo.compareTo(Duration.ofMinutes(5)) <= 0;
	}
	private static IllegalStateException invalida() {
		return new IllegalStateException("Configuración de almacenamiento documental inválida");
	}
	URI url() { return url; }
	String bucket() { return bucket; }
	String credencial() { return credencial; }
	Duration conexion() { return conexion; }
	Duration respuesta() { return respuesta; }
	@Override
	public String toString() { return "StoragePropiedades[privadas]"; }
}
