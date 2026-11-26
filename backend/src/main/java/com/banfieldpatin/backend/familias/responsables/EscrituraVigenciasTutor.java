package com.banfieldpatin.backend.familias.responsables;

import java.sql.SQLException;
import java.util.UUID;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Puerto de V16. Recibe la identidad de la sesión y claves ya validadas por el servicio; la base solo corrobora
 * rol y escuela del actor (el login PostgreSQL es compartido: no autentica identidades HTTP). Traduce únicamente los
 * SQLSTATE propios de V16 (clase BP) y los de concurrencia; cualquier otra violación se relanza sin mapear.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class EscrituraVigenciasTutor {

	/** {@code creada=false} indica una repetición idempotente de la solicitud original. */
	public record Alta(UUID vigenciaId, boolean creada) {
	}

	public record Edicion(long version, boolean cambio) {
	}

	private final JdbcClient jdbc;

	public EscrituraVigenciasTutor(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Alta crear(UsuarioAutenticado actor, UUID deportista, UUID tutor, TipoVinculoTutor tipo, UUID solicitud,
			String huella) {
		CierreVigenciasTutor.exigirAdmin(actor);
		return ejecutar(() -> jdbc.sql("SELECT * FROM gestion_patin.crear_vigencia_tutor(" +
				":escuela, :actor, :deportista, :tutor, CAST(:tipo AS text), :solicitud, :huella)")
				.param("escuela", actor.escuelaId()).param("actor", actor.id()).param("deportista", deportista)
				.param("tutor", tutor).param("tipo", tipo == null ? null : tipo.name()).param("solicitud", solicitud)
				.param("huella", huella)
				.query((rs, n) -> new Alta(rs.getObject(1, UUID.class), rs.getBoolean(2))).single());
	}

	public Edicion actualizarTipo(UsuarioAutenticado actor, UUID vigencia, long versionEsperada, TipoVinculoTutor tipo) {
		CierreVigenciasTutor.exigirAdmin(actor);
		return ejecutar(() -> jdbc.sql("SELECT * FROM gestion_patin.actualizar_tipo_vigencia_tutor(" +
				":escuela, :actor, :vigencia, :version, CAST(:tipo AS text))")
				.param("escuela", actor.escuelaId()).param("actor", actor.id()).param("vigencia", vigencia)
				.param("version", versionEsperada).param("tipo", tipo == null ? null : tipo.name())
				.query((rs, n) -> new Edicion(rs.getLong(1), rs.getBoolean(2))).single());
	}

	public long revocar(UsuarioAutenticado actor, UUID vigencia, long versionEsperada) {
		CierreVigenciasTutor.exigirAdmin(actor);
		return ejecutar(() -> jdbc.sql("SELECT gestion_patin.revocar_vigencia_tutor(:escuela, :actor, :vigencia, :version)")
				.param("escuela", actor.escuelaId()).param("actor", actor.id()).param("vigencia", vigencia)
				.param("version", versionEsperada).query(Long.class).single());
	}

	private static <T> T ejecutar(java.util.function.Supplier<T> operacion) {
		try {
			return operacion.get();
		} catch (DataAccessException e) {
			SQLException sql = causaSql(e);
			String estado = sql == null ? null : sql.getSQLState();
			if (estado == null) {
				throw e;
			}
			switch (estado) {
				case "BP404":
					throw new ExcepcionNegocio(HttpStatus.NOT_FOUND, "RECURSO_NO_ENCONTRADO", "El recurso no existe.");
				case "BP405":
					throw new ExcepcionNegocio(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUTILIZADA",
							"La clave de idempotencia ya se usó con otro contenido.");
				case "BP412":
					throw new ExcepcionNegocio(HttpStatus.CONFLICT, "ESTADO_INCOMPATIBLE",
							"El estado actual no permite esta operación.");
				case "BP413":
					throw new ExcepcionNegocio(HttpStatus.CONFLICT, "RESPONSABLE_YA_VIGENTE",
							"El tutor ya es responsable vigente del deportista.");
				case "BP414":
					throw new ExcepcionNegocio(HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA",
							"El registro fue modificado. Es necesario volver a cargarlo antes de guardar.");
				case "55P03", "40P01", "40001":
					throw new CannotAcquireLockException("Conflicto de concurrencia en la escritura de vigencias.", e);
				default:
					throw e;
			}
		}
	}

	private static SQLException causaSql(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql) {
				return sql;
			}
		}
		return null;
	}
}
