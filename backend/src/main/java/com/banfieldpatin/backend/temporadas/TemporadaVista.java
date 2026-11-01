package com.banfieldpatin.backend.temporadas;

import java.util.UUID;

/**
 * Lectura escalar de una temporada. A proposito NO es una entidad: leer el estado antes de tomar el bloqueo de la escuela
 * no debe dejar una entidad en el contexto de persistencia, porque tras el bloqueo la relectura devolveria esa instancia
 * (con el estado viejo) en lugar del estado que otra transaccion acaba de confirmar.
 */
public record TemporadaVista(UUID id, Short anio, boolean activa) {
}
