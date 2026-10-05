package com.banfieldpatin.backend.usuarios;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.banfieldpatin.backend.seguridad.VerificadorSesionVigente;

/**
 * Revalidacion central de la sesion contra PostgreSQL (REQ-XC-09, design 9.4): UNA sentencia por solicitud
 * autenticada, solo lookups por clave primaria, sin cache, sin transaccion ni Hibernate (auto-commit: sin BEGIN/COMMIT
 * extra). Mismas reglas que el login y {@code /api/auth/me} (usuario, escuela y, para FAMILIA, familia activos) mas la
 * coherencia de los reclamos: el {@code rol} y el {@code familia_id} del token deben ser los de la fila.
 * <p>
 * No registra identificadores. Una falla de la base se propaga como {@code DataAccessException}: nunca se responde
 * {@code true} ante un error.
 */
@Component
public class SesionVigenteJdbc implements VerificadorSesionVigente {

	private static final String SQL = """
			select exists (
			  select 1 from gestion_patin.usuario u
			  join gestion_patin.escuela e on e.id = u.escuela_id
			  left join gestion_patin.familia f on f.id = u.familia_id and f.escuela_id = u.escuela_id
			  where u.id = :usuarioId and u.escuela_id = :escuelaId and u.activo and e.activa and u.rol = :rol
			    and (u.rol <> 'FAMILIA' or (u.familia_id = :familiaId and f.activa)))
			""";

	private final JdbcClient jdbc;

	public SesionVigenteJdbc(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean vigente(UUID usuarioId, UUID escuelaId, String rol, UUID familiaId) {
		return Boolean.TRUE.equals(jdbc.sql(SQL)
				.param("usuarioId", usuarioId)
				.param("escuelaId", escuelaId)
				.param("rol", rol)
				.param("familiaId", familiaId)
				.query(Boolean.class)
				.single());
	}
}
