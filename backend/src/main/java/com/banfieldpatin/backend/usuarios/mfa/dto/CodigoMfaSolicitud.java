package com.banfieldpatin.backend.usuarios.mfa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Codigo TOTP: exactamente 6 digitos ASCII. Cualquier otro formato es un 400 uniforme, sin tocar el servicio. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CodigoMfaSolicitud(
		@NotBlank @Pattern(regexp = "[0-9]{6}", message = "debe tener exactamente 6 digitos") String codigo) {

	/** Evita que el codigo aparezca en logs si el record se imprime. */
	@Override
	public String toString() {
		return "CodigoMfaSolicitud[codigo=***]";
	}
}
