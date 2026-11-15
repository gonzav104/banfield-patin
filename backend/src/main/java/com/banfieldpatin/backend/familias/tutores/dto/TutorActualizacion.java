package com.banfieldpatin.backend.familias.tutores.dto;

import com.banfieldpatin.backend.deportistas.Dni;
import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Reemplazo completo (PUT) de un tutor. Exige las DOS revisiones observadas: la del tutor ({@code versionEsperada}) y la de
 * su familia ({@code versionFamiliaEsperada}), ambas cadenas decimales. Mismas reglas de normalizacion y mismo rechazo de
 * propiedades adicionales que {@link TutorSolicitud}.
 */
public record TutorActualizacion(
		@NotBlank @Size(max = 100) String nombre,
		@NotBlank @Size(max = 100) String apellido,
		@Size(max = 20) @Dni String dni,
		@Size(max = 40) String telefono,
		@Email @Size(max = 180) String email,
		@Size(max = 40) String parentesco,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revision del tutor observada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revision de la familia observada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionFamiliaEsperada)
		implements DatosTutor {

	public TutorActualizacion {
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

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "TutorActualizacion[***]";
	}
}
