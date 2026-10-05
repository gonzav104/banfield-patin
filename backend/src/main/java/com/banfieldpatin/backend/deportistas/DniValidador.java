package com.banfieldpatin.backend.deportistas;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DniValidador implements ConstraintValidator<Dni, String> {

	@Override
	public boolean isValid(String valor, ConstraintValidatorContext contexto) {
		return valor == null || DocumentoIdentidad.dniValido(valor);
	}
}
