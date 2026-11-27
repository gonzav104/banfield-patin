package com.banfieldpatin.backend.familias.perfil;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Banderas del autoservicio familiar (banfield.autoservicio.*, U21-BIS). {@code perfilHabilitado} (C1): con {@code false}
 * (valor por defecto) la familia puede consultar su perfil pero crear, editar o reclamar responde 503
 * {@code AUTOSERVICIO_PERFIL_DESHABILITADO} antes de leer o escribir nada.
 */
@ConfigurationProperties("banfield.autoservicio")
public record AutoservicioPropiedades(@DefaultValue("false") boolean perfilHabilitado) {
}
