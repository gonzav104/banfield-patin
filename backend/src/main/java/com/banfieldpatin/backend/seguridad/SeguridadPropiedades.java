package com.banfieldpatin.backend.seguridad;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion de seguridad. Valida al construirse (fail-fast) sin imprimir nunca el secreto.
 */
@ConfigurationProperties("banfield.seguridad")
public record SeguridadPropiedades(
		@DefaultValue Jwt jwt,
		@DefaultValue Cookie cookie,
		@DefaultValue Cors cors,
		@DefaultValue Login login,
		@DefaultValue Mfa mfa) {

	public static final int SECRETO_MIN_BYTES = 32;
	private static final Duration DURACION_MIN = Duration.ofMinutes(5);
	private static final Duration DURACION_MAX = Duration.ofHours(24);
	private static final Set<String> SAME_SITE_VALIDOS = Set.of("Strict", "Lax", "None");

	public record Jwt(
			String secreto,
			@DefaultValue("banfield-patin-backend") String emisor,
			@DefaultValue("PT8H") Duration duracion) {

		public Jwt {
			if (secreto == null || secreto.isBlank()) {
				throw new IllegalStateException("JWT_SECRET ausente o demasiado corto (minimo 32 bytes)");
			}
			byte[] decodificado;
			try {
				decodificado = Base64.getDecoder().decode(secreto.trim());
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException("JWT_SECRET debe estar codificado en base64");
			}
			if (decodificado.length < SECRETO_MIN_BYTES) {
				throw new IllegalStateException("JWT_SECRET ausente o demasiado corto (minimo 32 bytes)");
			}
			if (emisor == null || emisor.isBlank()) {
				throw new IllegalStateException("banfield.seguridad.jwt.emisor no puede estar vacio");
			}
			if (duracion == null || duracion.compareTo(DURACION_MIN) < 0 || duracion.compareTo(DURACION_MAX) > 0) {
				throw new IllegalStateException("banfield.seguridad.jwt.duracion debe estar entre PT5M y PT24H");
			}
		}

		public byte[] secretoBytes() {
			return Base64.getDecoder().decode(secreto.trim());
		}
	}

	/**
	 * Segundo factor de ADMIN. La clave AES-256 de cifrado del secreto TOTP no tiene valor por defecto: si falta o no
	 * son exactamente 32 bytes en base64 la aplicacion no arranca (igual que JWT_SECRET). Nunca se imprime.
	 */
	public record Mfa(
			String claveCifrado,
			@DefaultValue("PT5M") Duration duracionPendiente,
			@DefaultValue("Banfield Patin") String emisor) {

		public static final int CLAVE_BYTES = 32;
		private static final Duration PENDIENTE_MIN = Duration.ofMinutes(1);
		private static final Duration PENDIENTE_MAX = Duration.ofMinutes(15);
		private static final String MENSAJE_CLAVE = "MFA_CLAVE_CIFRADO ausente o invalida (base64 de exactamente 32 bytes)";

		public Mfa {
			if (claveCifrado == null || claveCifrado.isBlank()) {
				throw new IllegalStateException(MENSAJE_CLAVE);
			}
			byte[] decodificada;
			try {
				decodificada = Base64.getDecoder().decode(claveCifrado.trim());
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException(MENSAJE_CLAVE);
			}
			if (decodificada.length != CLAVE_BYTES) {
				throw new IllegalStateException(MENSAJE_CLAVE);
			}
			if (duracionPendiente == null || duracionPendiente.compareTo(PENDIENTE_MIN) < 0
					|| duracionPendiente.compareTo(PENDIENTE_MAX) > 0) {
				throw new IllegalStateException("banfield.seguridad.mfa.duracion-pendiente debe estar entre PT1M y PT15M");
			}
			if (emisor == null || emisor.isBlank() || emisor.contains(":")) {
				throw new IllegalStateException("banfield.seguridad.mfa.emisor no puede estar vacio ni contener ':'");
			}
		}

		public byte[] claveCifradoBytes() {
			return Base64.getDecoder().decode(claveCifrado.trim());
		}

		/** Evita que la clave aparezca en logs si el record se imprime. */
		@Override
		public String toString() {
			return "Mfa[claveCifrado=***, duracionPendiente=" + duracionPendiente + ", emisor=" + emisor + "]";
		}
	}

	public record Cookie(
			@DefaultValue("BP_SESION") String nombre,
			@DefaultValue("true") boolean secure,
			@DefaultValue("Lax") String sameSite) {

		public Cookie {
			if (nombre == null || nombre.isBlank()) {
				throw new IllegalStateException("banfield.seguridad.cookie.nombre no puede estar vacio");
			}
			if (sameSite == null || !SAME_SITE_VALIDOS.contains(sameSite)) {
				throw new IllegalStateException("banfield.seguridad.cookie.same-site debe ser Strict, Lax o None");
			}
			if ("None".equals(sameSite) && !secure) {
				throw new IllegalStateException("SameSite=None requiere cookie.secure=true");
			}
		}
	}

	public record Cors(@DefaultValue List<String> origenesPermitidos) {

		public Cors {
			origenesPermitidos = origenesPermitidos == null ? List.of()
					: origenesPermitidos.stream().filter(o -> o != null && !o.isBlank()).map(String::trim).toList();
		}
	}

	public record Login(
			@DefaultValue("5") int maxIntentos,
			@DefaultValue("PT15M") Duration ventana,
			@DefaultValue("PT15M") Duration bloqueo,
			@DefaultValue("10000") int maxClaves) {

		public Login {
			if (maxIntentos < 1 || maxClaves < 1 || ventana == null || bloqueo == null
					|| ventana.isNegative() || ventana.isZero() || bloqueo.isNegative() || bloqueo.isZero()) {
				throw new IllegalStateException("banfield.seguridad.login contiene valores invalidos");
			}
		}
	}
}
