package com.banfieldpatin.backend.familias.vinculos;

/**
 * Estados de un vinculo familia-deportista (CHECK ck_familia_deportista_estado de V1). Solo ACTIVO da acceso a la
 * FAMILIA. Esta API solo produce ACTIVO y REVOCADO; PENDIENTE y RECHAZADO existen en la base pero ningun flujo actual
 * los genera (un administrador que vincula una fila en esos estados la reutiliza como ACTIVO).
 */
public enum EstadoVinculo {
	PENDIENTE, ACTIVO, RECHAZADO, REVOCADO
}
