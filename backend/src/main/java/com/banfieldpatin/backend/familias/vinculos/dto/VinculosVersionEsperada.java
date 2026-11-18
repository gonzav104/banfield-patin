package com.banfieldpatin.backend.familias.vinculos.dto;

import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Comando ADMIN de revocar o cambiar el principal: la revision de los vinculos del deportista que se observo
 * ({@code versionVinculos}). Misma validacion que {@code DeportistaVersionEsperada}; no admite propiedades adicionales.
 */
public record VinculosVersionEsperada(@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revision de los vinculos del deportista observada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada) {

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}

	@Override
	public String toString() {
		return "VinculosVersionEsperada[***]";
	}
}
