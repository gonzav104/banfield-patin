package com.banfieldpatin.backend.familias.tutores.dto;

import java.util.Locale;

import com.banfieldpatin.backend.deportistas.Dni;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta y reemplazo completo (PUT) de un tutor. Todo se recorta; un opcional en blanco pasa a null y el email se guarda
 * en minusculas. El DNI conserva el formato recibido (hasta 20 caracteres, con puntos o espacios): el servicio lo
 * normaliza. La escuela, la familia, el usuario y el estado salen del contexto, nunca del cuerpo: cualquier otra
 * propiedad (familiaId, usuarioId, escuelaId, activo, ...) se rechaza con 400 SOLICITUD_INVALIDA sin eco del valor.
 */
public record TutorSolicitud(
		@NotBlank @Size(max = 100) String nombre,
		@NotBlank @Size(max = 100) String apellido,
		@Size(max = 20) @Dni String dni,
		@Size(max = 40) String telefono,
		@Email @Size(max = 180) String email,
		@Size(max = 40) String parentesco) {

	public TutorSolicitud {
		nombre = recortar(nombre);
		apellido = recortar(apellido);
		dni = opcional(dni);
		telefono = opcional(telefono);
		email = opcional(email) == null ? null : opcional(email).toLowerCase(Locale.ROOT);
		parentesco = opcional(parentesco);
	}

	private static String recortar(String valor) {
		return valor == null ? null : valor.strip();
	}

	private static String opcional(String valor) {
		String limpio = recortar(valor);
		return limpio == null || limpio.isEmpty() ? null : limpio;
	}

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "TutorSolicitud[***]";
	}
}
