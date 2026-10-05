package com.banfieldpatin.backend.deportistas;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CuilValidador implements ConstraintValidator<Cuil, String> {

	@Override
	public boolean isValid(String valor, ConstraintValidatorContext contexto) {
		return valor == null || DocumentoIdentidad.cuilValido(valor);
	}
}
