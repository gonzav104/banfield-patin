package com.banfieldpatin.backend.deportistas.dto;

import java.time.LocalDate;

import com.banfieldpatin.backend.deportistas.Cuil;
import com.banfieldpatin.backend.deportistas.Dni;
import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Reemplazo completo (PUT) de un deportista. Exige la revision observada ({@code versionEsperada}, cadena decimal).
 * Mismas reglas de normalizacion y mismo rechazo de propiedades adicionales que {@link DeportistaSolicitud}.
 */
public record DeportistaActualizacion(
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
		@Email @Size(max = 180) String emailFederativo,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revision del deportista observada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada)
		implements DatosDeportista {

	public DeportistaActualizacion {
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
		return "DeportistaActualizacion[***]";
	}
}
