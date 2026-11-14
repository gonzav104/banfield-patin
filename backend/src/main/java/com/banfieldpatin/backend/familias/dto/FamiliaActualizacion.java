package com.banfieldpatin.backend.familias.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;

/** Comando ADMIN de Familia: revision obligatoria; no admite propiedades adicionales. */
public record FamiliaActualizacion(@NotBlank @Size(max = 150) String nombreReferencia,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revision esperada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada) {

	public FamiliaActualizacion {
		nombreReferencia = nombreReferencia == null ? null : nombreReferencia.strip();
	}

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}

	@Override
	public String toString() {
		return "FamiliaActualizacion[***]";
	}
}
