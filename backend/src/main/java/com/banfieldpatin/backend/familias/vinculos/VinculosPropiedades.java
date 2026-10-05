package com.banfieldpatin.backend.familias.vinculos;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion de las transacciones de vinculos (banfield.vinculos.*).
 *
 * <p>{@code lockTimeout}: cuanto espera como maximo una transaccion de vinculos (vincular, revocar, cambiar principal) el
 * bloqueo de FILA del deportista ({@code FOR UPDATE}) antes de abandonar con 409 CONFLICTO_CONCURRENCIA. Por defecto 3 s.
 * Rango valido: 100 ms a 30 s (PostgreSQL interpreta 0 como "esperar para siempre", que es justo lo que se evita).
 */
@ConfigurationProperties("banfield.vinculos")
public record VinculosPropiedades(@DefaultValue("PT3S") Duration lockTimeout) {

	static final Duration MINIMO = Duration.ofMillis(100);
	static final Duration MAXIMO = Duration.ofSeconds(30);

	public VinculosPropiedades {
		if (lockTimeout == null || lockTimeout.compareTo(MINIMO) < 0 || lockTimeout.compareTo(MAXIMO) > 0) {
			throw new IllegalStateException("banfield.vinculos.lock-timeout debe estar entre 100 ms y 30 s");
		}
	}
}
