package com.banfieldpatin.backend.db;

import java.nio.ByteBuffer;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Calculo de TOTP (RFC 4226 / RFC 6238, HMAC-SHA1, 6 digitos, 30 s) escrito aparte de las clases de produccion
 * ({@code Totp}, {@code Base32}): reproduce lo que hace una aplicacion autenticadora sin reutilizar el codigo que se
 * prueba, para que un error de algoritmo en produccion no pueda quedar oculto por usar la misma implementacion.
 */
final class TotpIndependiente {

	private static final String ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

	private TotpIndependiente() {
	}

	static String codigo(String secretoBase32, long desfaseDePasos) {
		return codigoDePaso(secretoBase32, Instant.now().getEpochSecond() / 30 + desfaseDePasos);
	}

	static String codigoDePaso(String secretoBase32, long paso) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(decodificar(secretoBase32), "HmacSHA1"));
			byte[] h = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(paso).array());
			int o = h[h.length - 1] & 0x0f;
			int binario = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
			return "%06d".formatted(binario % 1_000_000);
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] decodificar(String base32) {
		var salida = new java.io.ByteArrayOutputStream();
		int acumulador = 0;
		int bits = 0;
		for (char c : base32.toCharArray()) {
			int valor = ALFABETO.indexOf(c);
			if (valor < 0) {
				throw new IllegalArgumentException("caracter Base32 invalido");
			}
			acumulador = (acumulador << 5) | valor;
			bits += 5;
			if (bits >= 8) {
				salida.write((acumulador >> (bits - 8)) & 0xff);
				bits -= 8;
			}
		}
		return salida.toByteArray();
	}
}
