package com.banfieldpatin.backend.compartido.bd;

import java.time.Duration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Acota la espera de un bloqueo de fila SOLO en la transaccion en curso.
 *
 * <p>Ejecuta {@code SET LOCAL lock_timeout} dentro de la transaccion (misma conexion que Hibernate: el
 * {@code JpaTransactionManager} expone su conexion JDBC al {@code JdbcClient}). {@code SET LOCAL} vale hasta el
 * COMMIT/ROLLBACK de esa transaccion y se descarta solo: nunca se filtra a otra transaccion ni a la conexion que vuelve
 * al pool, y no cambia nada de Hikari, de la sesion ni de otros servicios. Si el plazo vence, PostgreSQL responde 55P03
 * (lock_not_available) y Spring lo traduce a {@code CannotAcquireLockException}, que el manejador global convierte en 409
 * CONFLICTO_CONCURRENCIA. No es un bean: cada modulo la instancia con su propio {@code JdbcClient} y aplica su propio plazo.
 */
public class LockTimeoutTransaccion {

	private final JdbcClient jdbc;

	public LockTimeoutTransaccion(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Debe llamarse al INICIO de la transaccion, antes de la primera sentencia que pueda esperar un bloqueo. */
	public void aplicar(Duration plazo) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			// Fuera de una transaccion SET LOCAL no tendria efecto: es un error de programacion, no algo que tolerar.
			throw new IllegalStateException("lock_timeout requiere una transaccion activa");
		}
		// El valor es un long calculado (no hay texto del usuario): el literal es seguro y SET no admite parametros.
		jdbc.sql("SET LOCAL lock_timeout = '" + plazo.toMillis() + "ms'").update();
	}
}
