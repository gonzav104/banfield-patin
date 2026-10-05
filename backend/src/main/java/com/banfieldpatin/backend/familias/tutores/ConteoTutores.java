package com.banfieldpatin.backend.familias.tutores;

import java.util.UUID;

/** Fila de la consulta agrupada {@link TutorRepository#contarPorFamilia}. */
public record ConteoTutores(UUID familiaId, long cantidad) {
}
