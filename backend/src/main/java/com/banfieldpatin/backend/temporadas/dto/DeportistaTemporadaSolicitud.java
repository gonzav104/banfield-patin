package com.banfieldpatin.backend.temporadas.dto;

import java.util.UUID;

import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Alta de los datos deportivos de un deportista en una temporada. La escuela y el deportista salen del JWT y de la ruta;
 * cualquier otra propiedad (escuelaId, deportistaId, id, ...) se rechaza con 400 SOLICITUD_INVALIDA sin eco del valor. Todo
 * se recorta, lo vacio pasa a null y el estado por defecto es ACTIVO. Los largos son los del esquema.
 */
public record DeportistaTemporadaSolicitud(
		@NotNull UUID temporadaId,
		@Size(max = 80) @Pattern(regexp = TextoDeportivo.SIN_CONTROL) String categoria,
		@Size(max = 40) @Pattern(regexp = TextoDeportivo.SIN_CONTROL) String rama,
		@Size(max = 80) @Pattern(regexp = TextoDeportivo.SIN_CONTROL) String divisional,
		@Size(max = 30) @Pattern(regexp = TextoDeportivo.SIN_CONTROL) String numeroCorredor,
		EstadoDeportivo estadoDeportivo) {

	public DeportistaTemporadaSolicitud {
		categoria = TextoDeportivo.texto(categoria);
		rama = TextoDeportivo.texto(rama);
		divisional = TextoDeportivo.texto(divisional);
		numeroCorredor = TextoDeportivo.codigo(numeroCorredor);
		estadoDeportivo = estadoDeportivo == null ? EstadoDeportivo.ACTIVO : estadoDeportivo;
	}

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}
}
