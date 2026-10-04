package com.banfieldpatin.backend.seguridad;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Politica de contrasenas: no vacia, minimo configurable (10 por defecto) en caracteres y maximo 72 bytes UTF-8
 * (limite de bcrypt). Nunca incluye el valor evaluado en ningun mensaje.
 */
public class ValidadorContrasena implements ConstraintValidator<ContrasenaValida, String> {

	public static final int MIN_LONGITUD_POR_DEFECTO = 10;
	public static final int MAX_BYTES = 72;

	private final int minLongitud;

	public ValidadorContrasena() {
		this(MIN_LONGITUD_POR_DEFECTO);
	}

	public ValidadorContrasena(int minLongitud) {
		this.minLongitud = minLongitud;
	}

	@Override
	public boolean isValid(String contrasena, ConstraintValidatorContext contexto) {
		return esValida(contrasena);
	}

	public boolean esValida(String contrasena) {
		return contrasena != null
				&& !contrasena.isBlank()
				&& contrasena.codePointCount(0, contrasena.length()) >= minLongitud
				&& contrasena.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
	}
}
