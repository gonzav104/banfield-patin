package com.banfieldpatin.backend.deportistas.dto;

import java.time.LocalDate;
import java.util.Locale;

import com.banfieldpatin.backend.deportistas.Cuil;
import com.banfieldpatin.backend.deportistas.Dni;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

/**
 * Alta y reemplazo completo (PUT) de un deportista (solo datos permanentes). Todo se recorta; un opcional en blanco pasa
 * a null y el email se guarda en minusculas. DNI y CUIL conservan el formato recibido (hasta 20 caracteres, con puntos,
 * guiones o espacios): el servicio los normaliza. Una fecha mal formada hace fallar la lectura del cuerpo (400
 * SOLICITUD_INVALIDA). El estado, el id y la escuela salen del contexto, nunca del cuerpo: cualquier otra propiedad
 * (activo, id, escuelaId, ...) se rechaza con 400 SOLICITUD_INVALIDA sin eco del valor.
 */
public record DeportistaSolicitud(
		@NotBlank @Size(max = 20) @Dni String dni,
		@NotBlank @Size(max = 100) String nombre,
		@NotBlank @Size(max = 100) String apellido,
		@Size(max = 20) @Cuil String cuil,
		@Past LocalDate fechaNacimiento,
		@Size(max = 80) String nacionalidad,
		@Size(max = 180) String domicilio,
		@Size(max = 250) String otrosDatosDomicilio,
		@Size(max = 100) String localidad,
		@Size(max = 100) String partido,
		@Size(max = 15) String codigoPostal,
		@Size(max = 40) String telefonoContacto,
		@Email @Size(max = 180) String emailFederativo) {

	public DeportistaSolicitud {
		dni = recortar(dni);
		nombre = recortar(nombre);
		apellido = recortar(apellido);
		cuil = opcional(cuil);
		nacionalidad = opcional(nacionalidad);
		domicilio = opcional(domicilio);
		otrosDatosDomicilio = opcional(otrosDatosDomicilio);
		localidad = opcional(localidad);
		partido = opcional(partido);
		codigoPostal = opcional(codigoPostal);
		telefonoContacto = opcional(telefonoContacto);
		emailFederativo = opcional(emailFederativo) == null ? null : opcional(emailFederativo).toLowerCase(Locale.ROOT);
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
		return "DeportistaSolicitud[***]";
	}
}
