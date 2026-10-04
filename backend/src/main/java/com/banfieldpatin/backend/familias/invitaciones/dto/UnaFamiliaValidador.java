package com.banfieldpatin.backend.familias.invitaciones.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

class UnaFamiliaValidador implements ConstraintValidator<UnaFamilia, CrearInvitacionSolicitud> {

	@Override
	public boolean isValid(CrearInvitacionSolicitud solicitud, ConstraintValidatorContext contexto) {
		if (solicitud == null) {
			return true;
		}
		if ((solicitud.familiaId() != null) == (solicitud.nuevaFamilia() != null)) {
			contexto.disableDefaultConstraintViolation();
			contexto.buildConstraintViolationWithTemplate(contexto.getDefaultConstraintMessageTemplate())
					.addPropertyNode("familiaId").addConstraintViolation();
			return false;
		}
		return true;
	}
}
