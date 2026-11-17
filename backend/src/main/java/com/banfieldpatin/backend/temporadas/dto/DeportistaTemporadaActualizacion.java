package com.banfieldpatin.backend.temporadas.dto;

import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Reemplazo completo (PUT) de los datos deportivos. El deportista y la temporada son la identidad de la ruta y no se
 * pueden cambiar: cualquier otra propiedad (temporadaId, deportistaId, escuelaId, id, ...) se rechaza con 400
 * SOLICITUD_INVALIDA. A diferencia del alta, el estado deportivo es obligatorio: un reemplazo completo no adivina valores.
 * Exige ademas la revision de la fila que se observo ({@code versionEsperada}, cadena decimal no negativa dentro de BIGINT):
 * ausente o nula da 400 VALIDACION; malformada o fuera de rango da 400 SOLICITUD_INVALIDA, ambas sin eco del valor.
 */
public record DeportistaTemporadaActualizacion(
		@Size(max = 80) @Pattern(regexp = TextoDeportivo.SIN_CONTROL) String categoria,
		@Size(max = 40) @Pattern(regexp = TextoDeportivo.SIN_CONTROL) String rama,
		@Size(max = 80) @Pattern(regexp = TextoDeportivo.SIN_CONTROL) String divisional,
		@Size(max = 30) @Pattern(regexp = TextoDeportivo.SIN_CONTROL) String numeroCorredor,
		@NotNull EstadoDeportivo estadoDeportivo,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revision de la fila de datos deportivos observada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionEsperada) {

	public DeportistaTemporadaActualizacion {
		categoria = TextoDeportivo.texto(categoria);
		rama = TextoDeportivo.texto(rama);
		divisional = TextoDeportivo.texto(divisional);
		numeroCorredor = TextoDeportivo.codigo(numeroCorredor);
	}

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}
}
