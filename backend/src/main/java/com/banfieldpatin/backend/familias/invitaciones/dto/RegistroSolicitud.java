package com.banfieldpatin.backend.familias.invitaciones.dto;

import com.banfieldpatin.backend.seguridad.ContrasenaValida;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Los campos desconocidos (rol, escuelaId, familiaId, activo, ...) se ignoran a proposito: el rol lo fuerza el
 * servidor y la familia y la escuela salen de la invitacion. Esta decision (R7) esta cubierta por un test.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistroSolicitud(
		@NotBlank @Size(max = 100) String token,
		@NotBlank @Size(max = 100) String nombre,
		@NotBlank @Size(max = 100) String apellido,
		@NotBlank @Email @Size(max = 180) String email,
		@ContrasenaValida String password) {

	/** Evita que el token y la contrasena aparezcan en logs si el record se imprime. */
	@Override
	public String toString() {
		return "RegistroSolicitud[nombre=" + nombre + ", apellido=" + apellido + ", email=" + email
				+ ", token=***, password=***]";
	}
}
