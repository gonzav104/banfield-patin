package com.banfieldpatin.backend.familias.invitaciones.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * El token viaja en el cuerpo (nunca en la URL) para que no quede en logs de acceso. Solo se valida que no este
 * vacio ni sea desmesurado: un formato invalido lo resuelve el servicio con el mismo error uniforme que un token
 * inexistente, para no dar un oraculo de formato.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidarInvitacionSolicitud(@NotBlank @Size(max = 100) String token) {

	/** Evita que el token aparezca en logs si el record se imprime. */
	@Override
	public String toString() {
		return "ValidarInvitacionSolicitud[token=***]";
	}
}
