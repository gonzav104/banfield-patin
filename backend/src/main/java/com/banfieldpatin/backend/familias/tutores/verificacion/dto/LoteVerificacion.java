package com.banfieldpatin.backend.familias.tutores.verificacion.dto;

import java.util.List;
import java.util.UUID;

import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Verificación en lote (solo VERIFICAR, para tutores DECLARADOS sin observaciones): hasta {@value #MAXIMO} tutores distintos,
 * cada uno con la revisión que ADMIN vio. Cada tutor se resuelve en su propia transacción y tiene su propio resultado.
 */
public record LoteVerificacion(@NotEmpty @Size(max = MAXIMO) List<@NotNull @Valid Item> tutores) {

	public static final int MAXIMO = 50;

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}

	public record Item(@NotNull UUID tutorId,
			@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revisión del tutor observada")
			@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada) {

		@JsonAnySetter
		void rechazarPropiedadDesconocida(String nombre, Object valor) {
			throw new IllegalArgumentException("Propiedad no admitida");
		}
	}
}
