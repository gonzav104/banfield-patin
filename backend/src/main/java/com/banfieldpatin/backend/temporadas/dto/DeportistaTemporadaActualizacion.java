package com.banfieldpatin.backend.temporadas.dto;

import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Reemplazo completo (PUT) de los datos deportivos. El deportista y la temporada son la identidad de la ruta y no se
 * pueden cambiar: cualquier otra propiedad (temporadaId, deportistaId, escuelaId, id, ...) se rechaza con 400
 * SOLICITUD_INVALIDA. A diferencia del alta, el estado deportivo es obligatorio: un reemplazo completo no adivina valores.
 */
public record DeportistaTemporadaActualizacion(
		@Size(max = 80) @Pattern(regexp = TextoDeportivo.SIN_CONTROL) String categoria,
		@Size(max = 40) @Pattern(regexp = TextoDeportivo.SIN_CONTROL) String rama,
		@Size(max = 80) @Pattern(regexp = TextoDeportivo.SIN_CONTROL) String divisional,
		@Size(max = 30) @Pattern(regexp = TextoDeportivo.SIN_CONTROL) String numeroCorredor,
		@NotNull EstadoDeportivo estadoDeportivo) {

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
