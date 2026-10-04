package com.banfieldpatin.backend.usuarios.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Los campos desconocidos (escuelaId, rol, ...) se ignoran a proposito: la escuela y el rol los decide el servidor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginSolicitud(
		@NotBlank @Email @Size(max = 180) String email,
		@NotBlank @Size(max = 200) String password) {

	/** Evita que la contrasena aparezca en logs si el record se imprime. */
	@Override
	public String toString() {
		return "LoginSolicitud[email=" + email + ", password=***]";
	}
}
