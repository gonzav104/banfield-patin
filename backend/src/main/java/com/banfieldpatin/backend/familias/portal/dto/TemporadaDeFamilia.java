package com.banfieldpatin.backend.familias.portal.dto;

import java.util.UUID;

import com.banfieldpatin.backend.temporadas.EstadoDeportivo;

/**
 * Datos deportivos anuales de UN deportista propio para su FAMILIA, solo lectura. Muestra exclusivamente el anio, si es la
 * temporada vigente y los cinco datos deportivos; NO el id de la fila, ni {@code escuelaId}, ni {@code deportistaId} (ya
 * esta en la ruta), ni marcas de tiempo, ni auditoria, ni el DNI, y tampoco {@code deportista.activo}: el estado global del
 * deportista y su estado deportivo anual son planos distintos. Se construye con {@code select new} (una sola consulta con el
 * alcance de la familia).
 */
public record TemporadaDeFamilia(UUID temporadaId, Short anio, boolean temporadaActiva, String categoria, String rama,
		String divisional, String numeroCorredor, EstadoDeportivo estadoDeportivo) {

	/** Los datos de un menor no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "TemporadaDeFamilia[temporadaId=" + temporadaId + "]";
	}
}
