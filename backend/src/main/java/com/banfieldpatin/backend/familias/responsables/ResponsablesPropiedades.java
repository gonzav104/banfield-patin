package com.banfieldpatin.backend.familias.responsables;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de las altas de responsables (banfield.responsables.*).
 *
 * <p>{@code altaHabilitada}: control efectivo del backend. Con {@code false} (valor por defecto) el alta responde
 * 503 {@code ALTA_RESPONSABLES_DESHABILITADA} antes de leer o bloquear nada. No se habilita sin que los escritores
 * antiguos incompatibles estén retirados y los roles Flyway/runtime estén separados (docs/VINCULOS-TUTORES.md).
 */
@ConfigurationProperties("banfield.responsables")
public record ResponsablesPropiedades(@DefaultValue("false") boolean altaHabilitada) {
}
