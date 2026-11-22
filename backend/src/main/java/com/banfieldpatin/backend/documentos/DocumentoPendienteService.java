package com.banfieldpatin.backend.documentos;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Bandeja de pendientes de revisión: los documentos vigentes en estado CARGADO de la escuela de la sesión, del
 * deportista o del tutor, del más antiguo al más reciente (el id desempata). Solo lectura: no audita (igual que los
 * demás listados), no acepta parámetros de alcance y la revisión y la descarga siguen en las fichas. Dos consultas por
 * página (filas y total) sin consultas por resultado.
 */
@Service
public class DocumentoPendienteService {

    private static final String DESDE = """
            FROM gestion_patin.documento d
            LEFT JOIN gestion_patin.deportista dep ON dep.id = d.deportista_id AND dep.escuela_id = d.escuela_id
            LEFT JOIN gestion_patin.tutor tut ON tut.id = d.tutor_id AND tut.escuela_id = d.escuela_id
            WHERE d.escuela_id = :escuela AND d.vigente AND d.estado = 'CARGADO'""";
    /** Solo constantes: el filtro nunca se arma con entrada del cliente. */
    private static final String SOLO_DEPORTISTAS = " AND d.deportista_id IS NOT NULL";
    private static final String SOLO_TUTORES = " AND d.tutor_id IS NOT NULL";
    private static final String FILAS = """
            SELECT d.id, d.tipo, d.creado_en,
                   CASE WHEN d.deportista_id IS NOT NULL THEN 'DEPORTISTA' ELSE 'TUTOR' END,
                   COALESCE(d.deportista_id, d.tutor_id),
                   COALESCE(dep.nombre || ' ' || dep.apellido, tut.nombre || ' ' || tut.apellido),
                   tut.familia_id, d.documento_anterior_id IS NOT NULL
            """;
    private static final String ORDEN = " ORDER BY d.creado_en, d.id LIMIT :limite OFFSET :desde";

    private final JdbcClient jdbc;

    public DocumentoPendienteService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Pagina<DocumentoPendienteAdmin> listar(UsuarioAutenticado admin, TitularDocumento titular, Pageable pagina) {
        String filtro = titular == null ? "" : titular == TitularDocumento.DEPORTISTA ? SOLO_DEPORTISTAS : SOLO_TUTORES;
        var filas = jdbc.sql(FILAS + DESDE + filtro + ORDEN).param("escuela", admin.escuelaId())
                .param("limite", pagina.getPageSize()).param("desde", pagina.getOffset())
                .query((rs, n) -> fila(rs)).list();
        long total = jdbc.sql("SELECT count(*) " + DESDE + filtro).param("escuela", admin.escuelaId())
                .query(Long.class).single();
        Page<DocumentoPendienteAdmin> resultado = new PageImpl<>(filas, pagina, total);
        return Pagina.de(resultado, fila -> fila);
    }

    private static DocumentoPendienteAdmin fila(ResultSet rs) throws SQLException {
        return new DocumentoPendienteAdmin(rs.getObject(1, UUID.class), TipoDocumento.valueOf(rs.getString(2)),
                rs.getObject(3, java.time.OffsetDateTime.class).toInstant(), TitularDocumento.valueOf(rs.getString(4)),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getObject(7, UUID.class), rs.getBoolean(8));
    }
}
