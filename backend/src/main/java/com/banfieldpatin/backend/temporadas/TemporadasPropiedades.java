package com.banfieldpatin.backend.temporadas;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion de las transacciones de temporadas (banfield.temporadas.*).
 *
 * <p>{@code lockTimeout}: cuanto espera como maximo una activacion el bloqueo de FILA de la escuela antes de abandonar con
 * 409 CONFLICTO_CONCURRENCIA. Por defecto 3 s. Rango valido: 100 ms a 30 s (PostgreSQL interpreta 0 como "esperar para
 * siempre", que es justo lo que se evita).
 */
@ConfigurationProperties("banfield.temporadas")
public record TemporadasPropiedades(@DefaultValue("PT3S") Duration lockTimeout) {

	static final Duration MINIMO = Duration.ofMillis(100);
	static final Duration MAXIMO = Duration.ofSeconds(30);

	public TemporadasPropiedades {
		if (lockTimeout == null || lockTimeout.compareTo(MINIMO) < 0 || lockTimeout.compareTo(MAXIMO) > 0) {
			throw new IllegalStateException("banfield.temporadas.lock-timeout debe estar entre 100 ms y 30 s");
		}
	}
}
