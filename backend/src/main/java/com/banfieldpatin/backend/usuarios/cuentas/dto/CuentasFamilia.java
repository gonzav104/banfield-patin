package com.banfieldpatin.backend.usuarios.cuentas.dto;

import java.util.List;

/** Cuentas de una familia y si la gestión (desactivar/reactivar) está habilitada en este entorno. */
public record CuentasFamilia(boolean gestionHabilitada, List<CuentaFamiliar> cuentas) {
}
