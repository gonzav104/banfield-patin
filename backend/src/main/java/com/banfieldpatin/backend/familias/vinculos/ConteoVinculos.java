package com.banfieldpatin.backend.familias.vinculos;

import java.util.UUID;

/** Fila de la consulta agrupada {@link FamiliaDeportistaRepository#contarActivosPorFamilia}. */
public record ConteoVinculos(UUID familiaId, long cantidad) {
}
