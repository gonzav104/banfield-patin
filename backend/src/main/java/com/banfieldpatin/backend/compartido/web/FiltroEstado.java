package com.banfieldpatin.backend.compartido.web;

import org.springframework.http.HttpStatus;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;

/**
 * Filtro de estado (activo/inactivo) de los listados administrativos. {@link #nombre()} es el centinela que reciben
 * las consultas JPQL (en lugar de un parametro nulo).
 */
public enum FiltroEstado {
	TODOS, ACTIVOS, INACTIVOS;

	/** Valor ausente o en blanco = {@code porDefecto}; cualquier otro valor desconocido da 400 SOLICITUD_INVALIDA. */
	public static FiltroEstado de(String valor, FiltroEstado porDefecto) {
		if (valor == null || valor.isBlank()) {
			return porDefecto;
		}
		for (FiltroEstado filtro : values()) {
			if (filtro.name().equals(valor.strip())) {
				return filtro;
			}
		}
		throw new ExcepcionNegocio(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "La solicitud no es válida.");
	}
}
