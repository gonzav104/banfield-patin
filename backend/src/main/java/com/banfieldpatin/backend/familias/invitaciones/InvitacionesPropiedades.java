package com.banfieldpatin.backend.familias.invitaciones;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Vigencia de las invitaciones: por defecto 7 dias, maximo 30 (configurables en banfield.invitaciones.*). */
@ConfigurationProperties("banfield.invitaciones")
public record InvitacionesPropiedades(
		@DefaultValue("P7D") Duration vigenciaPorDefecto,
		@DefaultValue("P30D") Duration vigenciaMaxima) {

	public InvitacionesPropiedades {
		if (vigenciaPorDefecto == null || vigenciaMaxima == null || vigenciaPorDefecto.isNegative()
				|| vigenciaPorDefecto.isZero() || vigenciaMaxima.compareTo(vigenciaPorDefecto) < 0) {
			throw new IllegalStateException(
					"banfield.invitaciones: la vigencia por defecto debe ser positiva y no superar la maxima");
		}
	}
}
