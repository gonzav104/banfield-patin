package com.banfieldpatin.backend.temporadas.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cuerpo de {@code POST /api/admin/temporadas/{id}/activar}: la intencion del ADMIN. {@code activaEsperadaId} es la
 * temporada activa que observo al decidir, o {@code null} si no habia ninguna. La clave es OBLIGATORIA (su ausencia da 400
 * SOLICITUD_INVALIDA) para que una activacion nunca se confirme sin decir sobre que estado se decidio; solo su VALOR puede
 * ser {@code null}. Cualquier otra propiedad (escuelaId, anio, ...) se rechaza con 400 SOLICITUD_INVALIDA: la escuela sale
 * de la sesion, nunca del cliente. No es la revision de datos deportivos de un deportista ni una version de la fila.
 */
public record TemporadaActivacionSolicitud(
		@JsonProperty(required = true) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "Id de la temporada activa que el ADMIN observo, o null si no habia ninguna") UUID activaEsperadaId) {

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}
}
