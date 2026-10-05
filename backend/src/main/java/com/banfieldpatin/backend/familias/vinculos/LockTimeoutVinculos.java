package com.banfieldpatin.backend.familias.vinculos;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Acota la espera del bloqueo de fila del deportista SOLO en las transacciones de vinculos.
 *
 * <p>Ejecuta {@code SET LOCAL lock_timeout} dentro de la transaccion en curso (misma conexion que Hibernate: el
 * {@code JpaTransactionManager} expone su conexion JDBC al {@code JdbcClient}). {@code SET LOCAL} vale hasta el
 * COMMIT/ROLLBACK de esa transaccion y se descarta solo: nunca se filtra a otra transaccion ni a la conexion que vuelve
 * al pool, y no cambia nada de Hikari, de la sesion ni de otros servicios. Si el plazo vence, PostgreSQL responde 55P03
 * (lock_not_available) y Spring lo traduce a {@code CannotAcquireLockException}, que el manejador global convierte en 409
 * CONFLICTO_CONCURRENCIA.
 */
@Component
@EnableConfigurationProperties(VinculosPropiedades.class)
public class LockTimeoutVinculos {

	private final JdbcClient jdbc;
	private final long milisegundos;

	public LockTimeoutVinculos(JdbcClient jdbc, VinculosPropiedades propiedades) {
		this.jdbc = jdbc;
		this.milisegundos = propiedades.lockTimeout().toMillis();
	}

	/** Debe llamarse al INICIO de la transaccion, antes de la primera sentencia que pueda esperar un bloqueo. */
	public void aplicar() {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			// Fuera de una transaccion SET LOCAL no tendria efecto: es un error de programacion, no algo que tolerar.
			throw new IllegalStateException("lock_timeout de vinculos requiere una transaccion activa");
		}
		// El valor es un long validado (no hay texto del usuario): el literal es seguro y SET no admite parametros.
		jdbc.sql("SET LOCAL lock_timeout = '" + milisegundos + "ms'").update();
	}
}
