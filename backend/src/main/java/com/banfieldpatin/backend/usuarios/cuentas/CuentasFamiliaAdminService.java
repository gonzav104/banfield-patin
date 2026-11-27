package com.banfieldpatin.backend.usuarios.cuentas;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.cuentas.dto.CuentaFamiliar;
import com.banfieldpatin.backend.usuarios.cuentas.dto.CuentasFamilia;

/**
 * Cuentas de acceso de las personas de una familia (U21-BIS C0): ADMIN las ve y puede desactivarlas o reactivarlas.
 *
 * <ul>
 * <li><b>Alcance:</b> solo cuentas FAMILIA de una familia de la escuela del ADMIN; la familia y la cuenta se exigen juntas
 * (una cuenta de otra familia o de otra escuela, un ADMIN o un id inexistente dan el mismo 404). Nunca cambia la
 * pertenencia de una cuenta (V18 lo impide en la base).</li>
 * <li><b>Sesiones ya iniciadas:</b> desactivar las corta desde la siguiente solicitud (la sesión se revalida en la base en
 * cada solicitud); reactivar fija {@code sesiones_validas_desde} para que los tokens anteriores no revivan (V19).</li>
 * <li><b>Concurrencia sin versión:</b> cada acción expresa el estado final y se aplica con un UPDATE condicional sobre el
 * estado contrario. Repetirla (doble clic, otro ADMIN) devuelve el estado actual y no vuelve a auditar.</li>
 * <li><b>Auditoría:</b> USUARIO_DESACTIVADO / USUARIO_REACTIVADO con la familia; sin nombres ni correos.</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(CuentasPropiedades.class)
public class CuentasFamiliaAdminService {

	private static final String COLUMNAS = "u.id, u.nombre, u.apellido, u.email, u.activo, u.ultimo_acceso_en, u.creado_en";
	private static final String ALCANCE = """
			u.escuela_id = :escuela AND u.familia_id = :familia AND u.rol = 'FAMILIA'
			""";

	private final JdbcClient jdbc;
	private final AuditoriaService auditoria;
	private final CuentasPropiedades propiedades;
	private final Clock reloj;

	public CuentasFamiliaAdminService(JdbcClient jdbc, AuditoriaService auditoria, CuentasPropiedades propiedades,
			Clock reloj) {
		this.jdbc = jdbc;
		this.auditoria = auditoria;
		this.propiedades = propiedades;
		this.reloj = reloj;
	}

	public static ExcepcionNegocio cuentaNoEncontrada() {
		return new ExcepcionNegocio(HttpStatus.NOT_FOUND, "CUENTA_NO_ENCONTRADA", "La cuenta no existe.");
	}

	@Transactional(readOnly = true)
	public CuentasFamilia listar(UsuarioAutenticado admin, UUID familiaId) {
		exigirAdmin(admin);
		exigirFamilia(admin, familiaId);
		List<CuentaFamiliar> cuentas = jdbc.sql("SELECT " + COLUMNAS + " FROM gestion_patin.usuario u WHERE " + ALCANCE
				+ " ORDER BY u.creado_en, u.id")
				.param("escuela", admin.escuelaId()).param("familia", familiaId)
				.query(CuentasFamiliaAdminService::cuenta).list();
		return new CuentasFamilia(propiedades.gestionHabilitada(), cuentas);
	}

	@Transactional
	public CuentaFamiliar desactivar(UsuarioAutenticado admin, UUID familiaId, UUID cuentaId, DatosSolicitud solicitud) {
		return cambiar(admin, familiaId, cuentaId, false, solicitud);
	}

	@Transactional
	public CuentaFamiliar reactivar(UsuarioAutenticado admin, UUID familiaId, UUID cuentaId, DatosSolicitud solicitud) {
		return cambiar(admin, familiaId, cuentaId, true, solicitud);
	}

	private CuentaFamiliar cambiar(UsuarioAutenticado admin, UUID familiaId, UUID cuentaId, boolean activa,
			DatosSolicitud solicitud) {
		if (!propiedades.gestionHabilitada()) {
			throw new ExcepcionNegocio(HttpStatus.SERVICE_UNAVAILABLE, "GESTION_CUENTAS_DESHABILITADA",
					"La gestión de cuentas no está habilitada.");
		}
		exigirAdmin(admin);
		exigirFamilia(admin, familiaId);
		// Reactivar fija el corte de sesiones: un token emitido antes ya no vale (V19). Desactivar no lo necesita.
		String asignacion = activa ? "activo = true, sesiones_validas_desde = :ahora" : "activo = false";
		int cambiadas = jdbc.sql("UPDATE gestion_patin.usuario u SET " + asignacion + " WHERE u.id = :cuenta AND "
				+ ALCANCE + " AND u.activo = :antes")
				.param("cuenta", cuentaId).param("escuela", admin.escuelaId()).param("familia", familiaId)
				.param("antes", !activa)
				.param("ahora", OffsetDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC))
				.update();
		CuentaFamiliar cuenta = jdbc.sql("SELECT " + COLUMNAS + " FROM gestion_patin.usuario u WHERE u.id = :cuenta AND "
				+ ALCANCE)
				.param("cuenta", cuentaId).param("escuela", admin.escuelaId()).param("familia", familiaId)
				.query(CuentasFamiliaAdminService::cuenta).optional().orElseThrow(CuentasFamiliaAdminService::cuentaNoEncontrada);
		if (cambiadas == 1) {
			auditoria.registrar(new EventoAuditoria(admin.escuelaId(), admin.id(),
					activa ? AccionAuditoria.USUARIO_REACTIVADO : AccionAuditoria.USUARIO_DESACTIVADO, "USUARIO",
					cuentaId, Map.of("familiaId", familiaId.toString()), solicitud));
		}
		return cuenta;
	}

	private static void exigirAdmin(UsuarioAutenticado admin) {
		if (admin.rol() != Rol.ADMIN) {
			throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "No tenés permisos para esta acción.");
		}
	}

	private void exigirFamilia(UsuarioAutenticado admin, UUID familiaId) {
		boolean existe = Boolean.TRUE.equals(jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM gestion_patin.familia WHERE id = :familia AND escuela_id = :escuela)
				""").param("familia", familiaId).param("escuela", admin.escuelaId()).query(Boolean.class).single());
		if (!existe) {
			throw FamiliaAdminService.familiaNoEncontrada();
		}
	}

	private static CuentaFamiliar cuenta(ResultSet rs, int fila) throws SQLException {
		return new CuentaFamiliar(rs.getObject("id", UUID.class), rs.getString("nombre"), rs.getString("apellido"),
				rs.getString("email"), rs.getBoolean("activo"), instante(rs.getTimestamp("ultimo_acceso_en")),
				instante(rs.getTimestamp("creado_en")));
	}

	private static Instant instante(Timestamp t) {
		return t == null ? null : t.toInstant();
	}
}
