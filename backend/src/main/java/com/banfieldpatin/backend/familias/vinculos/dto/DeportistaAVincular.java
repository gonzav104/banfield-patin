package com.banfieldpatin.backend.familias.vinculos.dto;

import java.util.UUID;

import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Un elemento del lote de vinculacion: el deportista y la revision de SUS vinculos que se observo
 * ({@code versionVinculos}). Misma validacion que las demas revisiones (cadena decimal dentro de BIGINT); no admite
 * propiedades adicionales.
 */
public record DeportistaAVincular(@NotNull UUID deportistaId,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revision de los vinculos del deportista observada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada) {

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}

	@Override
	public String toString() {
		return "DeportistaAVincular[***]";
	}
}
