package com.banfieldpatin.backend.usuarios.cuentas;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de la gestión de cuentas por ADMIN (banfield.cuentas.*, U21-BIS C0).
 *
 * <p>{@code gestionHabilitada}: con {@code false} (valor por defecto) desactivar o reactivar una cuenta responde 503
 * {@code GESTION_CUENTAS_DESHABILITADA} antes de leer o escribir nada. El listado de cuentas sigue disponible e informa
 * el valor para que la pantalla no ofrezca acciones que fallarían.
 */
@ConfigurationProperties("banfield.cuentas")
public record CuentasPropiedades(@DefaultValue("false") boolean gestionHabilitada) {
}
