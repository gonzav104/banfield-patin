package com.banfieldpatin.backend.familias.responsables;

import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.error.RestriccionViolada;
import com.banfieldpatin.backend.familias.vinculos.LockTimeoutVinculos;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/**
 * Puerto de V15. Recibe la identidad de la sesión y claves cargadas por escuela por el servicio.
 * V15 toma los bloqueos padre → marca → vigencias y audita cada cierre al confirmar.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class CierreVigenciasTutor {
	private static final String SQL = """
			SELECT * FROM gestion_patin.cerrar_vigencias_tutor(
				:escuela, :actor, CAST(:causa AS text), :objetivo, CAST(:familia AS uuid))
			""";

	private final JdbcClient jdbc;
	private final LockTimeoutVinculos lockTimeout;

	public CierreVigenciasTutor(JdbcClient jdbc, LockTimeoutVinculos lockTimeout) {
		this.jdbc = jdbc;
		this.lockTimeout = lockTimeout;
	}

	public static void exigirAdmin(UsuarioAutenticado actor) {
		if (actor.rol() != Rol.ADMIN) {
			throw new AccessDeniedException("Acceso denegado.");
		}
	}

	public void deFamilia(UsuarioAutenticado actor, UUID familia) {
		lockTimeout.aplicar();
		cerrar(actor, "FAMILIA_INACTIVA", familia, null);
	}

	public void deDeportista(UsuarioAutenticado actor, UUID deportista) {
		lockTimeout.aplicar();
		cerrar(actor, "DEPORTISTA_INACTIVO", deportista, null);
	}

	public void deTutor(UsuarioAutenticado actor, UUID familia, UUID tutor) {
		lockTimeout.aplicar();
		cerrar(actor, "TUTOR_INACTIVO", tutor, familia);
	}

	/** El servicio de vínculos ya aplicó su plazo y bloqueó al deportista. */
	public void deVinculo(UsuarioAutenticado actor, UUID familia, UUID deportista) {
		cerrar(actor, "FAMILIA_DEPORTISTA_REVOCADA", deportista, familia);
	}

	/**
	 * Si el snapshot de REPEATABLE READ no ve un alta concurrente, la FK inmediata de la
	 * marca impide la baja. El cliente debe repetir el comando en una nueva transacción.
	 */
	public static <T> T confirmarBaja(String fkDeLaMarca, Supplier<T> escribirPadre) {
		try {
			return escribirPadre.get();
		} catch (DataIntegrityViolationException e) {
			if (e.getMostSpecificCause() instanceof SQLException sql && "23503".equals(sql.getSQLState())
					&& RestriccionViolada.nombre(e).filter(fkDeLaMarca::equals).isPresent()) {
				throw new CannotAcquireLockException("Conflicto de concurrencia al confirmar la baja.", e);
			}
			throw e;
		}
	}

	private void cerrar(UsuarioAutenticado actor, String causa, UUID objetivo, UUID familia) {
		exigirAdmin(actor);
		try {
			jdbc.sql(SQL).param("escuela", actor.escuelaId()).param("actor", actor.id())
					.param("causa", causa).param("objetivo", objetivo).param("familia", familia)
					.query(UUID.class).list();
		} catch (UncategorizedSQLException e) {
			// El traductor de JdbcClient no clasifica igual todos los errores del driver.
			String estado = e.getSQLException().getSQLState();
			if ("55P03".equals(estado) || "40P01".equals(estado) || "40001".equals(estado)) {
				throw new CannotAcquireLockException("Conflicto de concurrencia al cerrar vigencias.", e);
			}
			throw e;
		}
	}
}
