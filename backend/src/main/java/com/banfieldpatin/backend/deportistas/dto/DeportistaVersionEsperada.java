package com.banfieldpatin.backend.deportistas.dto;

import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Comando ADMIN de estado de Deportista (activar/desactivar): revision obligatoria; no admite propiedades adicionales. */
public record DeportistaVersionEsperada(@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revision del deportista observada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada) {

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}

	@Override
	public String toString() {
		return "DeportistaVersionEsperada[***]";
	}
}
