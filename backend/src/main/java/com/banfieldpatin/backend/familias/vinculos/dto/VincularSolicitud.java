package com.banfieldpatin.backend.familias.vinculos.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lote de deportistas a vincular a la familia de la ruta. Maximo 50 ids (se cuentan ANTES de quitar duplicados) y ningun
 * item nulo. El estado, el caracter principal y quien autoriza los decide el servidor: cualquier otra propiedad
 * (esPrincipal, estado, autorizadoPor, familiaId, escuelaId, ...) se rechaza con 400 SOLICITUD_INVALIDA.
 */
public record VincularSolicitud(@NotNull @Size(min = 1, max = 50) List<@NotNull UUID> deportistaIds) {

	public VincularSolicitud {
		// Copia defensiva que tolera items nulos: Bean Validation los informa como 400 VALIDACION.
		deportistaIds = deportistaIds == null ? null : Collections.unmodifiableList(new ArrayList<>(deportistaIds));
	}

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}
}
