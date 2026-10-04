package com.banfieldpatin.backend.familias.invitaciones;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * Genera tokens de invitacion: 32 bytes (256 bits) de SecureRandom en Base64 URL-safe sin relleno (43 caracteres).
 * Solo el hash SHA-256 (hexadecimal minuscula, 64 caracteres) se persiste; el token en claro se devuelve una unica vez.
 */
@Component
public class GeneradorTokenInvitacion {

	static final int BYTES = 32;

	private final SecureRandom random;

	public GeneradorTokenInvitacion() {
		this(new SecureRandom());
	}

	GeneradorTokenInvitacion(SecureRandom random) {
		this.random = random;
	}

	public String generar() {
		byte[] bytes = new byte[BYTES];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public static String sha256Hex(String token) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 no disponible", e);
		}
	}
}
