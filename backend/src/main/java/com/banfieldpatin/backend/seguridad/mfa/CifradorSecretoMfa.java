package com.banfieldpatin.backend.seguridad.mfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.banfieldpatin.backend.seguridad.SeguridadPropiedades;

/**
 * Cifrado en reposo del secreto TOTP con AES-256-GCM. Salida = nonce aleatorio de 12 bytes + texto cifrado +
 * etiqueta de 16 bytes. El id del usuario va como AAD: un secreto copiado a la fila de otro usuario no descifra.
 * Un fallo de autenticacion (dato alterado, AAD distinto o clave equivocada) lanza IllegalStateException sin
 * ningun dato sensible en el mensaje.
 */
@Component
public class CifradorSecretoMfa {

	static final int NONCE_BYTES = 12;
	static final int ETIQUETA_BITS = 128;
	private static final String TRANSFORMACION = "AES/GCM/NoPadding";

	private final SecretKeySpec clave;
	private final SecureRandom azar = new SecureRandom();

	/** Constructor de Spring. @Autowired explicito: con dos constructores Spring exigiria uno sin argumentos. */
	@Autowired
	public CifradorSecretoMfa(SeguridadPropiedades propiedades) {
		this(propiedades.mfa().claveCifradoBytes());
	}

	private CifradorSecretoMfa(byte[] clave) {
		this.clave = new SecretKeySpec(clave, "AES");
	}

	/** Para pruebas que necesitan otra clave sin armar las propiedades completas. */
	static CifradorSecretoMfa conClave(byte[] clave32) {
		if (clave32.length != SeguridadPropiedades.Mfa.CLAVE_BYTES) {
			throw new IllegalArgumentException("La clave debe tener 32 bytes");
		}
		return new CifradorSecretoMfa(clave32);
	}

	public byte[] cifrar(byte[] secreto, UUID usuarioId) {
		byte[] nonce = new byte[NONCE_BYTES];
		azar.nextBytes(nonce);
		try {
			Cipher cifrador = Cipher.getInstance(TRANSFORMACION);
			cifrador.init(Cipher.ENCRYPT_MODE, clave, new GCMParameterSpec(ETIQUETA_BITS, nonce));
			cifrador.updateAAD(aad(usuarioId));
			byte[] cifrado = cifrador.doFinal(secreto);
			return ByteBuffer.allocate(nonce.length + cifrado.length).put(nonce).put(cifrado).array();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("No se pudo cifrar el secreto MFA");
		}
	}

	public byte[] descifrar(byte[] almacenado, UUID usuarioId) {
		if (almacenado == null || almacenado.length <= NONCE_BYTES + ETIQUETA_BITS / 8) {
			throw new IllegalStateException("No se pudo descifrar el secreto MFA");
		}
		try {
			Cipher cifrador = Cipher.getInstance(TRANSFORMACION);
			cifrador.init(Cipher.DECRYPT_MODE, clave, new GCMParameterSpec(ETIQUETA_BITS, almacenado, 0, NONCE_BYTES));
			cifrador.updateAAD(aad(usuarioId));
			return cifrador.doFinal(almacenado, NONCE_BYTES, almacenado.length - NONCE_BYTES);
		} catch (GeneralSecurityException e) {
			// Sin la causa: el mensaje de AEADBadTagException no aporta nada y no debe filtrarse a logs ni respuestas.
			throw new IllegalStateException("No se pudo descifrar el secreto MFA");
		}
	}

	private static byte[] aad(UUID usuarioId) {
		return usuarioId.toString().getBytes(StandardCharsets.UTF_8);
	}
}
