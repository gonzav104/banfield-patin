package com.banfieldpatin.backend.documentos.apto;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.documentos.EstadoDocumento;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.TemporadaAdminService;
import com.banfieldpatin.backend.usuarios.Rol;

/**
 * Lectura del apto físico de un deportista en una temporada (U21). Mismo alcance que el listado de documentos y el checklist U20:
 * ADMIN, un deportista de su escuela (incluso inactivo); FAMILIA, un deportista de su familia activa con vínculo ACTIVO, y un único
 * 404 DEPORTISTA_NO_ENCONTRADO para todo lo demás. El alcance va en la consulta, nunca después. Solo lectura y sin auditoría
 * (no entrega el archivo; eso es la descarga auditada).
 */
@Service
public class AptoFisicoService {

    private final JdbcClient jdbc;
    private final DeportistaRepository deportistas;
    private final FamiliaDeportistaRepository vinculos;
    private final Clock reloj;

    public AptoFisicoService(JdbcClient jdbc, DeportistaRepository deportistas, FamiliaDeportistaRepository vinculos,
            Clock reloj) {
        this.jdbc = jdbc;
        this.deportistas = deportistas;
        this.vinculos = vinculos;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public AptoFisicoTemporada consultar(UsuarioAutenticado usuario, UUID deportistaId, UUID temporadaId) {
        boolean admin = usuario.rol() == Rol.ADMIN;
        if (admin) {
            if (!deportistas.existsByIdAndEscuelaId(deportistaId, usuario.escuelaId())) {
                throw DeportistaAdminService.deportistaNoEncontrado();
            }
        } else if (usuario.rol() != Rol.FAMILIA) {
            throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado.");
        } else if (usuario.familiaId() == null
                || vinculos.deportistaEnAlcance(usuario.familiaId(), usuario.escuelaId(), deportistaId).isEmpty()) {
            throw DeportistaAdminService.deportistaNoEncontrado();
        }
        Integer anio = jdbc.sql("SELECT anio FROM gestion_patin.temporada WHERE id=:t AND escuela_id=:e")
                .param("t", temporadaId).param("e", usuario.escuelaId()).query(Integer.class).optional()
                .orElseThrow(TemporadaAdminService::temporadaNoEncontrada);
        ReglaVigenciaApto.Vigencia regla = ReglaVigenciaApto.paraTemporada(anio);
        // Procedencia: los datos son de la FAMILIA cuyo usuario cargó esta versión (documento.cargado_por → usuario.familia_id). Otra
        // familia con vínculo activo ve el estado y la vigencia, no los datos; ADMIN ve todo. El archivo nunca sale por acá.
        var documento = jdbc.sql("""
                SELECT d.id, d.estado, d.fecha_emision, d.medico_nombre, d.medico_matricula, d.motivo_observacion, d.creado_en,
                       d.version,
                       EXISTS (SELECT 1 FROM gestion_patin.usuario u WHERE u.id = d.cargado_por
                               AND u.escuela_id = d.escuela_id AND u.familia_id = :familia) AS propia
                FROM gestion_patin.documento d
                WHERE d.escuela_id=:e AND d.deportista_id=:d AND d.temporada_id=:t AND d.tipo='APTO_FISICO' AND d.vigente
                """).param("e", usuario.escuelaId()).param("d", deportistaId).param("t", temporadaId)
                .param("familia", admin ? null : usuario.familiaId())
                .query((rs, n) -> {
                    boolean datos = admin || rs.getBoolean("propia");
                    return new AptoFisicoTemporada.DocumentoApto(rs.getObject("id", UUID.class), rs.getString("estado"),
                            datos ? rs.getObject("fecha_emision", LocalDate.class) : null,
                            datos ? rs.getString("medico_nombre") : null, datos ? rs.getString("medico_matricula") : null,
                            // El motivo lo escribe ADMIN y es lo que la familia necesita para actuar: viaja con el estado.
                            EstadoDocumento.OBSERVADO.name().equals(rs.getString("estado"))
                                    ? rs.getString("motivo_observacion") : null,
                            rs.getObject("creado_en", OffsetDateTime.class).toInstant(), admin ? rs.getLong("version") : null,
                            datos);
                }).optional().orElse(null);
        LocalDate hoy = LocalDate.now(reloj.withZone(ReglaVigenciaApto.ZONA));
        EstadoApto estado = CalculadoraEstadoApto.calcular(
                documento == null ? null : EstadoDocumento.valueOf(documento.estado()), regla, hoy);
        return new AptoFisicoTemporada(deportistaId, temporadaId, anio, estado, false,
                new AptoFisicoTemporada.Vigencia(regla.desde(), regla.hasta(), regla.confirmada(), regla.fuente()),
                regla.confirmada() ? List.of() : List.of(AptoFisicoTemporada.AVISO_REQUISITOS_SIN_CONFIRMAR), documento);
    }
}
