package com.banfieldpatin.backend.temporadas.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Alta de una temporada. La escuela sale del JWT y el estado nace inactivo y cambia solo por activar: cualquier otra
 * propiedad (escuelaId, activa, estado, ...) se rechaza con 400 SOLICITUD_INVALIDA (el any-setter lanza y Jackson lo
 * reporta como cuerpo ilegible, sin eco del valor). El rango es el del CHECK de V1. El anio se lee de forma ESTRICTA
 * ({@link AnioEstricto}): solo un entero JSON; un decimal o una cadena numerica dan 400 SOLICITUD_INVALIDA.
 */
public record TemporadaSolicitud(
		@NotNull @Min(TemporadaSolicitud.ANIO_MINIMO) @Max(TemporadaSolicitud.ANIO_MAXIMO)
		@JsonDeserialize(using = AnioEstricto.class) Integer anio) {

	public static final int ANIO_MINIMO = 2020;
	public static final int ANIO_MAXIMO = 2100;

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}
}
