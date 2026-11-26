package com.banfieldpatin.backend.familias.responsables;

import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Único dato editable de una vigencia abierta: el tipo declarado ({@code null} lo deja sin declarar). */
public record ResponsableTipo(
		TipoVinculoTutor tipo,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revisión de la vigencia observada")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada) {

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}

	@Override
	public String toString() {
		return "ResponsableTipo[***]";
	}
}
