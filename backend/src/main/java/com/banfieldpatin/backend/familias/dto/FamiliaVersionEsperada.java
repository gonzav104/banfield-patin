package com.banfieldpatin.backend.familias.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;

/** Comando ADMIN de Familia: revision obligatoria; no admite propiedades adicionales. */
public record FamiliaVersionEsperada(@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revision esperada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada) {

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}

	@Override
	public String toString() {
		return "FamiliaVersionEsperada[***]";
	}
}
