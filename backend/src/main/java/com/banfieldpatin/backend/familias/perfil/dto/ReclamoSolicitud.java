package com.banfieldpatin.backend.familias.perfil.dto;

import java.util.UUID;

import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** «Soy esta persona»: el tutor de MI familia que reclamo y la revisión que vi. Nada más: ni familia ni cuenta en el cuerpo. */
public record ReclamoSolicitud(
		@NotNull UUID tutorId,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revisión del tutor observada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada) {

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}
}
