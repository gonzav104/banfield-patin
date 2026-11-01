package com.banfieldpatin.backend.familias.vinculos;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.banfieldpatin.backend.compartido.bd.LockTimeoutTransaccion;

/**
 * Acota la espera del bloqueo de fila del deportista SOLO en las transacciones de vinculos: aplica el plazo de
 * {@link VinculosPropiedades} con el mecanismo comun {@link LockTimeoutTransaccion}.
 */
@Component
@EnableConfigurationProperties(VinculosPropiedades.class)
public class LockTimeoutVinculos {

	private final LockTimeoutTransaccion lockTimeout;
	private final Duration plazo;

	public LockTimeoutVinculos(JdbcClient jdbc, VinculosPropiedades propiedades) {
		this.lockTimeout = new LockTimeoutTransaccion(jdbc);
		this.plazo = propiedades.lockTimeout();
	}

	/** Debe llamarse al INICIO de la transaccion, antes de la primera sentencia que pueda esperar un bloqueo. */
	public void aplicar() {
		lockTimeout.aplicar(plazo);
	}
}
