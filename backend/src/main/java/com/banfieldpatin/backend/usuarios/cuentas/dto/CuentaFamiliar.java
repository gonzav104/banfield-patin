package com.banfieldpatin.backend.usuarios.cuentas.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Cuenta de acceso de una persona de la familia, tal como la ve ADMIN. Lista cerrada: sin contraseña, sin datos de
 * segundo factor ni de sesiones. {@code ultimoAccesoEn} es null si nunca ingresó.
 */
public record CuentaFamiliar(UUID id, String nombre, String apellido, String email, boolean activa,
		Instant ultimoAccesoEn, Instant creadaEn) {
}
