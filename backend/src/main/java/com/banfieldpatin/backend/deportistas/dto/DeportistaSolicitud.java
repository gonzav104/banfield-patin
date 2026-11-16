package com.banfieldpatin.backend.deportistas.dto;

import java.time.LocalDate;

import com.banfieldpatin.backend.deportistas.Cuil;
import com.banfieldpatin.backend.deportistas.Dni;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

/**
 * Alta de un deportista (solo datos permanentes); el reemplazo completo usa {@link DeportistaActualizacion}. Todo se recorta; un opcional en blanco pasa
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
		@Email @Size(max = 180) String emailFederativo) implements DatosDeportista {

	public DeportistaSolicitud {
		dni = DatosDeportista.recortar(dni);
		nombre = DatosDeportista.recortar(nombre);
		apellido = DatosDeportista.recortar(apellido);
		cuil = DatosDeportista.opcional(cuil);
		nacionalidad = DatosDeportista.opcional(nacionalidad);
		domicilio = DatosDeportista.opcional(domicilio);
		otrosDatosDomicilio = DatosDeportista.opcional(otrosDatosDomicilio);
		localidad = DatosDeportista.opcional(localidad);
		partido = DatosDeportista.opcional(partido);
		codigoPostal = DatosDeportista.opcional(codigoPostal);
		telefonoContacto = DatosDeportista.opcional(telefonoContacto);
		emailFederativo = DatosDeportista.emailNormalizado(emailFederativo);
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
