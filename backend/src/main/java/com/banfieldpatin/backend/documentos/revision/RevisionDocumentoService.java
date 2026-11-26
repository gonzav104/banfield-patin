package com.banfieldpatin.backend.documentos.revision;

import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.bd.LockTimeoutTransaccion;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.documentos.DocumentoAdminMetadatos;
import com.banfieldpatin.backend.documentos.EstadoDocumento;
import com.banfieldpatin.backend.documentos.TipoDocumento;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

@Service
public class RevisionDocumentoService {
    private static final Duration PLAZO_BLOQUEO = Duration.ofSeconds(2);

    /** Dueño y tipos revisables: los DNI (frente y dorso) y, desde U21, el apto físico del deportista; los DNI del tutor (U19). */
    private record Dueno(String columna, String tipos) {
        String alcance() {
            return "WHERE id = :documento AND escuela_id = :escuela AND " + columna + " = :dueno AND tipo IN ("
                    + tipos + ")\n";
        }
    }

    private static final Dueno DEPORTISTA = new Dueno("deportista_id",
            "'DNI_DEPORTISTA_FRENTE', 'DNI_DEPORTISTA_DORSO', 'APTO_FISICO'");
    private static final Dueno TUTOR = new Dueno("tutor_id", "'DNI_TUTOR_FRENTE', 'DNI_TUTOR_DORSO'");

    private final JdbcClient jdbc;
    private final AuditoriaService auditoria;
    private final LockTimeoutTransaccion bloqueo;

    public RevisionDocumentoService(JdbcClient jdbc, AuditoriaService auditoria) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.bloqueo = new LockTimeoutTransaccion(jdbc);
    }

    @Transactional
    public DocumentoAdminMetadatos revisar(UsuarioAutenticado usuario, UUID deportista, UUID documento,
            EstadoDocumento destino, long esperada, String motivo) {
        return revisar(usuario, DEPORTISTA, deportista, documento, destino, esperada, motivo);
    }

    /** Revisión de un DNI de un tutor (U19): mismas transiciones, CAS, bloqueo acotado y auditoría. */
    @Transactional
    public DocumentoAdminMetadatos revisarTutor(UsuarioAutenticado usuario, UUID tutor, UUID documento,
            EstadoDocumento destino, long esperada, String motivo) {
        return revisar(usuario, TUTOR, tutor, documento, destino, esperada, motivo);
    }

    private DocumentoAdminMetadatos revisar(UsuarioAutenticado usuario, Dueno dueno, UUID idDueno, UUID documento,
            EstadoDocumento destino, long esperada, String motivo) {
        if (usuario.rol() != Rol.ADMIN) {
            throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado.");
        }
        try {
            return revisarEnTransaccion(usuario, dueno, idDueno, documento, destino, esperada, motivo);
        } catch (UncategorizedSQLException e) {
            // JdbcClient no siempre traduce 55P03 como Hibernate; limitar el mapeo a este comando.
            String estadoSql = e.getSQLException().getSQLState();
            if ("55P03".equals(estadoSql) || "40P01".equals(estadoSql)) {
                throw new CannotAcquireLockException("Conflicto de concurrencia documental.");
            }
            throw e;
        }
    }

    private DocumentoAdminMetadatos revisarEnTransaccion(UsuarioAutenticado usuario, Dueno dueno, UUID idDueno,
            UUID documento, EstadoDocumento destino, long esperada, String motivo) {
        bloqueo.aplicar(PLAZO_BLOQUEO);
        var actual = acotar("""
                SELECT estado, vigente, version FROM gestion_patin.documento
                """ + dueno.alcance() + " FOR UPDATE", usuario, idDueno, documento)
                .query((rs, n) -> new DocumentoActual(EstadoDocumento.valueOf(rs.getString(1)),
                        rs.getBoolean(2), rs.getLong(3)))
                .optional().orElseThrow(() -> new ExcepcionNegocio(HttpStatus.NOT_FOUND,
                        "DOCUMENTO_NO_ENCONTRADO", "Documento no encontrado."));

        if (!actual.vigente()) {
            throw conflicto("DOCUMENTO_NO_VIGENTE", "El documento ya no está vigente.");
        }
        if (actual.version() != esperada) {
            throw desactualizada();
        }
        if (!permitida(actual.estado(), destino)) {
            throw conflicto("TRANSICION_DOCUMENTO_NO_PERMITIDA", "El estado actual no permite esta revisión.");
        }
        if (esperada == Long.MAX_VALUE) {
            throw conflicto("VERSION_DOCUMENTO_AGOTADA", "No se puede incrementar la versión del documento.");
        }

        var revisado = acotar("""
                UPDATE gestion_patin.documento
                SET estado = :destino, motivo_observacion = :motivo,
                    revisado_por = :actor, revisado_en = clock_timestamp(), version = version + 1
                """ + dueno.alcance() + """
                  AND vigente AND version = :version AND estado = :origen
                RETURNING id, tipo, estado, temporada_id, creado_en, vigente, fecha_vencimiento,
                          motivo_observacion, version
                """, usuario, idDueno, documento)
                .param("destino", destino.name()).param("motivo", motivo, Types.VARCHAR).param("actor", usuario.id())
                .param("version", esperada).param("origen", actual.estado().name())
                .query((rs, n) -> new DocumentoAdminMetadatos(rs.getObject(1, UUID.class),
                        TipoDocumento.valueOf(rs.getString(2)), EstadoDocumento.valueOf(rs.getString(3)),
                        rs.getObject(4, UUID.class), rs.getObject(5, OffsetDateTime.class).toInstant(),
                        rs.getBoolean(6), rs.getObject(7, LocalDate.class), rs.getString(8), rs.getLong(9)))
                .optional().orElseThrow(RevisionDocumentoService::desactualizada);

        auditoria.registrar(new EventoAuditoria(usuario.escuelaId(), usuario.id(), AccionAuditoria.DOCUMENTO_REVISADO,
                "DOCUMENTO", documento, Map.of("estadoAnterior", actual.estado().name(), "estadoNuevo", destino.name()),
                DatosSolicitud.NINGUNA));
        return revisado;
    }

    static boolean permitida(EstadoDocumento origen, EstadoDocumento destino) {
        return origen == EstadoDocumento.CARGADO
                && (destino == EstadoDocumento.APROBADO || destino == EstadoDocumento.OBSERVADO)
                || origen == EstadoDocumento.APROBADO && destino == EstadoDocumento.OBSERVADO;
    }

    private JdbcClient.StatementSpec acotar(String sql, UsuarioAutenticado usuario, UUID dueno, UUID documento) {
        return jdbc.sql(sql).param("documento", documento).param("escuela", usuario.escuelaId())
                .param("dueno", dueno);
    }

    private static ExcepcionNegocio desactualizada() {
        return conflicto("EDICION_DESACTUALIZADA", "El documento cambió. Volvé a cargarlo antes de revisar.");
    }

    private static ExcepcionNegocio conflicto(String codigo, String mensaje) {
        return new ExcepcionNegocio(HttpStatus.CONFLICT, codigo, mensaje);
    }

    private record DocumentoActual(EstadoDocumento estado, boolean vigente, long version) {}
}
