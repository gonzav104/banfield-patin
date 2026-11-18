package com.banfieldpatin.backend.familias.vinculos;

import java.util.UUID;

/**
 * Revision de los vinculos de un deportista leida de la base con una proyeccion escalar (nunca de una entidad gestionada
 * del contexto de persistencia): ver {@link FamiliaDeportistaRepository#revisionesVinculos}.
 */
public record RevisionVinculos(UUID deportistaId, long versionVinculos) {
}
