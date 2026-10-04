package com.banfieldpatin.backend.seguridad.mfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP de RFC 6238 (HOTP de RFC 4226) solo con el JDK: HMAC-SHA1, 6 digitos y paso de 30 s, que es lo que
 * entienden todas las aplicaciones autenticadoras. Acepta el paso actual y uno de margen a cada lado (+-1).
 * Las comparaciones son de tiempo constante y nada de lo que se calcula aqui se escribe en logs.
 */
public final class Totp {

	public static final int DIGITOS = 6;
	public static final long PASO_SEGUNDOS = 30;
	/** Pasos de margen aceptados a cada lado del actual (desfase de reloj entre servidor y dispositivo). */
	public static final int MARGEN_PASOS = 1;
	public static final int LONGITUD_SECRETO_BYTES = 20;

	private static final String HMAC = "HmacSHA1";
	private static final int[] POTENCIAS_10 = { 1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000,
			100_000_000 };

	private Totp() {
	}

	public static long pasoDe(Instant instante) {
		return Math.floorDiv(instante.getEpochSecond(), PASO_SEGUNDOS);
	}

	public static String codigo(byte[] secreto, long paso) {
		return codigo(secreto, paso, DIGITOS);
	}

	/** Visible para el paquete: los vectores de prueba de RFC 6238 usan 8 digitos. */
	static String codigo(byte[] secreto, long paso, int digitos) {
		byte[] hmac = hmac(secreto, paso);
		int desplazamiento = hmac[hmac.length - 1] & 0x0F;
		int binario = ((hmac[desplazamiento] & 0x7F) << 24)
				| ((hmac[desplazamiento + 1] & 0xFF) << 16)
				| ((hmac[desplazamiento + 2] & 0xFF) << 8)
				| (hmac[desplazamiento + 3] & 0xFF);
		String texto = Integer.toString(binario % POTENCIAS_10[digitos]);
		return "0".repeat(digitos - texto.length()) + texto;
	}

	/**
	 * Busca el paso cuyo codigo coincide con el recibido dentro de la ventana +-1 y mayor que el ultimo paso ya usado
	 * (anti-repeticion). Calcula y compara siempre los tres pasos, sin cortar al primer acierto.
	 *
	 * @param ultimoPasoUsado ultimo paso aceptado antes, o null si nunca se uso
	 * @return el paso aceptado, o vacio si el codigo no es valido
	 */
	public static OptionalLong verificar(byte[] secreto, String codigoRecibido, Instant ahora, Long ultimoPasoUsado) {
		byte[] recibido = codigoRecibido.getBytes(StandardCharsets.UTF_8);
		long actual = pasoDe(ahora);
		long aceptado = -1;
		boolean encontrado = false;
		for (int d = -MARGEN_PASOS; d <= MARGEN_PASOS; d++) {
			long paso = actual + d;
			byte[] esperado = codigo(secreto, Math.max(paso, 0)).getBytes(StandardCharsets.UTF_8);
			boolean coincide = MessageDigest.isEqual(esperado, recibido);
			boolean noUsado = ultimoPasoUsado == null || paso > ultimoPasoUsado;
			if (coincide && noUsado && paso >= 0 && paso > aceptado) {
				aceptado = paso;
				encontrado = true;
			}
		}
		return encontrado ? OptionalLong.of(aceptado) : OptionalLong.empty();
	}

	private static byte[] hmac(byte[] secreto, long paso) {
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(new SecretKeySpec(secreto, HMAC));
			return mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(paso).array());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC-SHA1 no disponible", e);
		}
	}
}
