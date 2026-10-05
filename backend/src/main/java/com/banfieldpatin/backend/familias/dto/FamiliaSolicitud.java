package com.banfieldpatin.backend.familias.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta y reemplazo completo (PUT) de una familia. La escuela sale del JWT y el estado solo cambia por
 * activar/desactivar: cualquier otra propiedad (escuelaId, activa, activo, estado, ...) se rechaza con
 * 400 SOLICITUD_INVALIDA (el any-setter lanza y Jackson lo reporta como cuerpo ilegible, sin eco del valor).
 */
public record FamiliaSolicitud(@NotBlank @Size(max = 150) String nombreReferencia) {

	public FamiliaSolicitud {
		nombreReferencia = nombreReferencia == null ? null : nombreReferencia.strip();
	}

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}
}
