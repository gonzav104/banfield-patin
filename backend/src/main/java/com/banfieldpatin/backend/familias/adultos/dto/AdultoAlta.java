package com.banfieldpatin.backend.familias.adultos.dto;

import com.banfieldpatin.backend.deportistas.Dni;
import com.banfieldpatin.backend.familias.tutores.dto.DatosTutor;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Otro adulto que la familia carga (U21-BIS C2), sin cuenta. Escuela, familia y quien declara salen del token, nunca del cuerpo:
 * cualquier otra propiedad se rechaza con 400 sin eco del valor. Todo se recorta; un opcional en blanco pasa a null; el correo
 * se guarda en minúsculas.
 */
public record AdultoAlta(
		@NotBlank @Size(max = 100) String nombre,
		@NotBlank @Size(max = 100) String apellido,
		@Size(max = 20) @Dni String dni,
		@Size(max = 40) String telefono,
		@Email @Size(max = 180) String email,
		@Size(max = 40) String parentesco) implements DatosTutor {

	public AdultoAlta {
		nombre = DatosTutor.recortar(nombre);
		apellido = DatosTutor.recortar(apellido);
		dni = DatosTutor.opcional(dni);
		telefono = DatosTutor.opcional(telefono);
		email = DatosTutor.emailNormalizado(email);
		parentesco = DatosTutor.opcional(parentesco);
	}

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}

	@Override
	public String toString() {
		return "AdultoAlta[***]";
	}
}
