package com.banfieldpatin.backend.familias.perfil.dto;

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
 * Reemplazo completo del perfil propio con la revisión que la persona vio ({@code versionEsperada}, cadena decimal): si otra
 * persona de la familia o ADMIN lo cambió mientras tanto, se rechaza con 409 sin pisar nada. Identidad (nombre, apellido, DNI)
 * y contacto (teléfono, correo, parentesco) se distinguen en el servicio: una identidad verificada no se cambia desde acá.
 */
public record PerfilActualizacion(
		@NotBlank @Size(max = 100) String nombre,
		@NotBlank @Size(max = 100) String apellido,
		@Size(max = 20) @Dni String dni,
		@Size(max = 40) String telefono,
		@Email @Size(max = 180) String email,
		@Size(max = 40) String parentesco,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revisión del perfil observada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada) implements DatosTutor {

	public PerfilActualizacion {
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
		return "PerfilActualizacion[***]";
	}
}
