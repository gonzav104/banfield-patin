package com.banfieldpatin.backend.temporadas;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.banfieldpatin.backend.compartido.bd.LockTimeoutTransaccion;

/** Acota la espera del bloqueo de la escuela SOLO en la activacion de temporadas ({@link TemporadasPropiedades}). */
@Component
@EnableConfigurationProperties(TemporadasPropiedades.class)
public class LockTimeoutTemporadas {

	private final LockTimeoutTransaccion lockTimeout;
	private final Duration plazo;

	public LockTimeoutTemporadas(JdbcClient jdbc, TemporadasPropiedades propiedades) {
		this.lockTimeout = new LockTimeoutTransaccion(jdbc);
		this.plazo = propiedades.lockTimeout();
	}

	/** Debe llamarse al INICIO de la transaccion, antes de la primera sentencia que pueda esperar un bloqueo. */
	public void aplicar() {
		lockTimeout.aplicar(plazo);
	}
}
