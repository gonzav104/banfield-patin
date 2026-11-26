package com.banfieldpatin.backend.familias.tutores.dto;

import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Los comandos de estado solo aceptan la revisión observada del tutor. */
public record TutorVersionEsperada(
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revisión del tutor observada")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada) {

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}

	@Override
	public String toString() {
		return "TutorVersionEsperada[***]";
	}
}
