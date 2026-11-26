package com.banfieldpatin.backend.documentos.apto;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.banfieldpatin.backend.documentos.EstadoDocumento;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Corrección administrativa de los datos de un apto físico (U21). Política, decidida de antemano y nunca silenciosa:
 * <ul>
 * <li>solo la versión VIGENTE y solo en estado CARGADO o APROBADO; un apto OBSERVADO no se corrige, se reemplaza (la familia
 * trae una versión nueva con sus propios datos) y una versión histórica no se toca nunca;</li>
 * <li>una corrección que cambia algo DEJA el documento en CARGADO: la aprobación se retira en la misma sentencia (nunca queda un
 * apto APROBADO con datos distintos a los que se revisaron) y vuelve a la bandeja de pendientes; V17 lo impone con un trigger;</li>
 * <li>una corrección que no cambia nada no escribe, no audita y conserva la aprobación;</li>
 * <li>revisión optimista sobre la versión del documento; la auditoría nombra actor, documento y CAMPOS modificados, jamás valores.</li>
 * </ul>
 */
@Service
public class CorreccionAptoService {
    private static final Duration PLAZO_BLOQUEO = Duration.ofSeconds(2);

    private final JdbcClient jdbc;
    private final AuditoriaService auditoria;
    private final Clock reloj;
    private final LockTimeoutTransaccion bloqueo;

    public CorreccionAptoService(JdbcClient jdbc, AuditoriaService auditoria, Clock reloj) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.reloj = reloj;
        this.bloqueo = new LockTimeoutTransaccion(jdbc);
    }

    @Transactional
    public AptoFisicoTemporada.DocumentoApto corregir(UsuarioAutenticado usuario, UUID deportista, UUID documento,
            long esperada, MetadatosApto.Solicitado solicitado) {
        if (usuario.rol() != Rol.ADMIN) {
            throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado.");
        }
        MetadatosApto nuevos = MetadatosApto.validar(solicitado, reloj);
        try {
            return corregirEnTransaccion(usuario, deportista, documento, esperada, nuevos);
        } catch (UncategorizedSQLException e) {
            String estadoSql = e.getSQLException().getSQLState();
            if ("55P03".equals(estadoSql) || "40P01".equals(estadoSql)) {
                throw new CannotAcquireLockException("Conflicto de concurrencia documental.");
            }
            throw e;
        }
    }

    private AptoFisicoTemporada.DocumentoApto corregirEnTransaccion(UsuarioAutenticado usuario, UUID deportista,
            UUID documento, long esperada, MetadatosApto nuevos) {
        bloqueo.aplicar(PLAZO_BLOQUEO);
        var actual = jdbc.sql("""
                SELECT estado, vigente, version, fecha_emision, medico_nombre, medico_matricula
                FROM gestion_patin.documento
                WHERE id = :d AND escuela_id = :e AND deportista_id = :p AND tipo = 'APTO_FISICO'
                FOR UPDATE
                """).param("d", documento).param("e", usuario.escuelaId()).param("p", deportista)
                .query((rs, n) -> new Actual(EstadoDocumento.valueOf(rs.getString(1)), rs.getBoolean(2), rs.getLong(3),
                        new MetadatosApto(rs.getObject(4, LocalDate.class), rs.getString(5), rs.getString(6))))
                .optional().orElseThrow(() -> new ExcepcionNegocio(HttpStatus.NOT_FOUND, "DOCUMENTO_NO_ENCONTRADO",
                        "Documento no encontrado."));
        if (!actual.vigente()) {
            throw conflicto("DOCUMENTO_NO_VIGENTE", "El documento ya no está vigente.");
        }
        if (actual.version() != esperada) {
            throw conflicto("EDICION_DESACTUALIZADA", "El documento cambió. Volvé a cargarlo antes de corregir.");
        }
        if (actual.estado() != EstadoDocumento.CARGADO && actual.estado() != EstadoDocumento.APROBADO) {
            throw conflicto("APTO_NO_CORREGIBLE", "Solo se corrige un apto en revisión o aprobado; uno observado se reemplaza.");
        }
        var campos = camposModificados(actual.datos(), nuevos);
        if (campos.isEmpty()) {
            return leer(documento, usuario);
        }
        if (esperada == Long.MAX_VALUE) {
            throw conflicto("VERSION_DOCUMENTO_AGOTADA", "No se puede incrementar la versión del documento.");
        }
        // Estado nuevo CARGADO en la MISMA sentencia: V17 rechaza cambiar datos de un apto aprobado sin retirar la aprobación.
        int filas = jdbc.sql("""
                UPDATE gestion_patin.documento
                SET fecha_emision = :f, medico_nombre = :n, medico_matricula = :m, estado = 'CARGADO',
                    motivo_observacion = NULL, revisado_por = NULL, revisado_en = NULL, version = version + 1
                WHERE id = :d AND escuela_id = :e AND deportista_id = :p AND tipo = 'APTO_FISICO' AND vigente
                  AND version = :v AND estado = :origen
                """).param("f", nuevos.fechaEmision()).param("n", nuevos.medicoNombre()).param("m", nuevos.medicoMatricula())
                .param("d", documento).param("e", usuario.escuelaId()).param("p", deportista).param("v", esperada)
                .param("origen", actual.estado().name()).update();
        if (filas != 1) {
            throw conflicto("EDICION_DESACTUALIZADA", "El documento cambió. Volvé a cargarlo antes de corregir.");
        }
        var detalle = new LinkedHashMap<String, Object>();
        detalle.put("campos", String.join(",", campos));
        detalle.put("estadoAnterior", actual.estado().name());
        detalle.put("estadoNuevo", EstadoDocumento.CARGADO.name());
        detalle.put("aprobacionRetirada", actual.estado() == EstadoDocumento.APROBADO);
        auditoria.registrar(new EventoAuditoria(usuario.escuelaId(), usuario.id(), AccionAuditoria.DOCUMENTO_APTO_CORREGIDO,
                "DOCUMENTO", documento, detalle, DatosSolicitud.NINGUNA));
        return leer(documento, usuario);
    }

    private AptoFisicoTemporada.DocumentoApto leer(UUID documento, UsuarioAutenticado usuario) {
        return jdbc.sql("""
                SELECT id, estado, fecha_emision, medico_nombre, medico_matricula, creado_en, version
                FROM gestion_patin.documento WHERE id = :d AND escuela_id = :e
                """).param("d", documento).param("e", usuario.escuelaId())
                .query((rs, n) -> new AptoFisicoTemporada.DocumentoApto(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, LocalDate.class), rs.getString(4), rs.getString(5), null,
                        rs.getObject(6, OffsetDateTime.class).toInstant(), rs.getLong(7)))
                .single();
    }

    /** Nombres (nunca valores) de los datos que cambian, en un orden estable. */
    static java.util.List<String> camposModificados(MetadatosApto actual, MetadatosApto nuevos) {
        var campos = new ArrayList<String>();
        if (!actual.fechaEmision().equals(nuevos.fechaEmision())) campos.add("fechaEmision");
        if (!actual.medicoNombre().equals(nuevos.medicoNombre())) campos.add("medicoNombre");
        if (!actual.medicoMatricula().equals(nuevos.medicoMatricula())) campos.add("medicoMatricula");
        return campos;
    }

    private static ExcepcionNegocio conflicto(String codigo, String mensaje) {
        return new ExcepcionNegocio(HttpStatus.CONFLICT, codigo, mensaje);
    }

    private record Actual(EstadoDocumento estado, boolean vigente, long version, MetadatosApto datos) {
    }
}
