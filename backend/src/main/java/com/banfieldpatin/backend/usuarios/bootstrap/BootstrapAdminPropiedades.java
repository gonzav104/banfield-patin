package com.banfieldpatin.backend.usuarios.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Datos del primer ADMIN. Deshabilitado por defecto; toString no expone email ni password. */
@ConfigurationProperties("banfield.bootstrap-admin")
public record BootstrapAdminPropiedades(
		@DefaultValue("false") boolean habilitado,
		String email,
		String nombre,
		String apellido,
		String password) {

	@Override
	public String toString() {
		return "BootstrapAdminPropiedades[habilitado=" + habilitado + "]";
	}
}
