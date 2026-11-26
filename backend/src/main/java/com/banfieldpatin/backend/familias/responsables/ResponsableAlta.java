package com.banfieldpatin.backend.familias.responsables;

import java.util.UUID;

import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Alta individual de un responsable. {@code tipo} es opcional: ausente o {@code null} queda sin declarar, que es
 * distinto de {@code SIN_DETERMINAR}. Las cuatro revisiones son las observadas por ADMIN y se verifican bajo bloqueo.
 */
public record ResponsableAlta(
		@NotNull UUID tutorId,
		TipoVinculoTutor tipo,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revisión de la familia del tutor observada")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionFamilia,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revisión del deportista observada")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionDeportista,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revisión del tutor observada")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionTutor,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revisión de los vínculos del deportista observada")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionVinculos) {

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}

	@Override
	public String toString() {
		return "ResponsableAlta[***]";
	}
}
