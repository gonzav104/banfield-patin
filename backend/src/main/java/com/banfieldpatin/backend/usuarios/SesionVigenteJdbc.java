package com.banfieldpatin.backend.usuarios;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.banfieldpatin.backend.seguridad.VerificadorSesionVigente;

/**
 * Revalidacion central de la sesion contra PostgreSQL (REQ-XC-09, design 9.4): UNA sentencia por solicitud
 * autenticada, solo lookups por clave primaria, sin cache, sin transaccion ni Hibernate (auto-commit: sin BEGIN/COMMIT
 * extra). Mismas reglas que el login y {@code /api/auth/me} (usuario, escuela y, para FAMILIA, familia activos) mas la
 * coherencia de los reclamos: el {@code rol} y el {@code familia_id} del token deben ser los de la fila; y el token no
 * puede ser anterior a la ultima reactivacion de la cuenta ({@code sesiones_validas_desde}, V19).
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
			    and (u.rol <> 'FAMILIA' or (u.familia_id = :familiaId and f.activa))
			    and (u.sesiones_validas_desde is null or cast(:emitidoEn as timestamptz) is null
			         or cast(:emitidoEn as timestamptz) >= date_trunc('second', u.sesiones_validas_desde)))
			""";

	private final JdbcClient jdbc;

	public SesionVigenteJdbc(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean vigente(UUID usuarioId, UUID escuelaId, String rol, UUID familiaId, Instant emitidoEn) {
		return Boolean.TRUE.equals(jdbc.sql(SQL)
				.param("usuarioId", usuarioId)
				.param("escuelaId", escuelaId)
				.param("rol", rol)
				.param("familiaId", familiaId)
				.param("emitidoEn", emitidoEn == null ? null : OffsetDateTime.ofInstant(emitidoEn, ZoneOffset.UTC))
				.query(Boolean.class)
				.single());
	}
}
