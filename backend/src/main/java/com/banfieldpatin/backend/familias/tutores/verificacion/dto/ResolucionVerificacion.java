package com.banfieldpatin.backend.familias.tutores.verificacion.dto;

import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Decisión de ADMIN sobre los datos de un tutor, con la revisión que vio. {@code OBSERVAR} exige un motivo (lo verá la familia;
 * no se audita); {@code VERIFICAR} no admite motivo.
 */
public record ResolucionVerificacion(
		@NotNull DecisionVerificacion decision,
		@Size(max = 500) String motivo,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revisión del tutor observada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada) {

	public ResolucionVerificacion {
		motivo = motivo == null || motivo.strip().isEmpty() ? null : motivo.strip();
	}

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}

	@Override
	public String toString() {
		return "ResolucionVerificacion[decision=" + decision + "]";
	}
}
