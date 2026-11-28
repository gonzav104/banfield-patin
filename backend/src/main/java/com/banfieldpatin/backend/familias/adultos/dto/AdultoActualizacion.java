package com.banfieldpatin.backend.familias.adultos.dto;

import com.banfieldpatin.backend.deportistas.Dni;
import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.banfieldpatin.backend.familias.tutores.dto.DatosTutor;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Reemplazo completo de los datos de un adulto de la propia familia con la revisión observada ({@code versionEsperada},
 * cadena decimal). El DNI nunca se devuelve completo a la familia: si se omite (o viene en blanco) se conserva el cargado. Una
 * identidad respaldada (verificada o con un DNI aprobado) no se cambia desde acá.
 */
public record AdultoActualizacion(
		@NotBlank @Size(max = 100) String nombre,
		@NotBlank @Size(max = 100) String apellido,
		@Size(max = 20) @Dni String dni,
		@Size(max = 40) String telefono,
		@Email @Size(max = 180) String email,
		@Size(max = 40) String parentesco,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revisión del adulto observada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada) implements DatosTutor {

	public AdultoActualizacion {
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
		return "AdultoActualizacion[***]";
	}
}
