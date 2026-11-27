package com.banfieldpatin.backend.documentos;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
/** Solo proyecciones; sin save/delete/findAll. Filas y conteo comparten exactamente el alcance. */
public interface DocumentoRepository extends Repository<Documento, UUID> {
    String CAMPOS_METADATOS = """
 doc.id, doc.tipo, doc.estado, doc.temporadaId, doc.creadoEn, doc.vigente, doc.fechaVencimiento,
 case when doc.estado = com.banfieldpatin.backend.documentos.EstadoDocumento.OBSERVADO
 then doc.motivoObservacion else null end
 """;
    String PROYECCION = "select new com.banfieldpatin.backend.documentos.DocumentoMetadatos("
            + CAMPOS_METADATOS + ")";
    /**
     * Proyección para la FAMILIA (U21): el motivo de observación de un APTO_FISICO solo lo recibe la familia que cargó esa versión
     * (documento.cargado_por → usuario.familia_id); a otra familia vinculada le llega el estado OBSERVADO y nada más. Para los demás
     * tipos la política no cambia.
     */
    String PROYECCION_FAMILIA = "select new com.banfieldpatin.backend.documentos.DocumentoMetadatos("
            + """
 doc.id, doc.tipo, doc.estado, doc.temporadaId, doc.creadoEn, doc.vigente, doc.fechaVencimiento,
 case when doc.estado = com.banfieldpatin.backend.documentos.EstadoDocumento.OBSERVADO
   and (doc.tipo <> com.banfieldpatin.backend.documentos.TipoDocumento.APTO_FISICO
        or exists (select u.id from Usuario u where u.id = doc.cargadoPor and u.escuelaId = doc.escuelaId
                   and u.familiaId = :familiaId))
 then doc.motivoObservacion else null end
 """ + ")";
    String PROYECCION_ADMIN = "select new com.banfieldpatin.backend.documentos.DocumentoAdminMetadatos("
            + CAMPOS_METADATOS + ", doc.version)";
    String ORDEN = " order by doc.creadoEn desc, doc.id";
    String DEPORTISTA = " from Documento doc where doc.escuelaId = :escuelaId and doc.deportistaId = :propietarioId and doc.vigente = true";
    String TUTOR = " from Documento doc where doc.escuelaId = :escuelaId and doc.tutorId = :propietarioId and doc.vigente = true";
    String DEPORTISTA_FAMILIA = DEPORTISTA + " and exists (select fd.id " + FamiliaDeportistaRepository.ALCANCE_FAMILIA + " and d.id = doc.deportistaId)";
    String TUTOR_FAMILIA = TUTOR + " and exists (select t.id " + TutorRepository.ALCANCE_FAMILIA + " and t.id = doc.tutorId)";

    @Query(value = PROYECCION_ADMIN + DEPORTISTA + ORDEN, countQuery = "select count(doc)" + DEPORTISTA)
    Page<DocumentoAdminMetadatos> deportistaAdmin(@Param("escuelaId") UUID escuelaId,
            @Param("propietarioId") UUID propietarioId, Pageable pagina);

    @Query(value = PROYECCION_ADMIN + TUTOR + ORDEN, countQuery = "select count(doc)" + TUTOR)
    Page<DocumentoAdminMetadatos> tutorAdmin(@Param("escuelaId") UUID escuelaId,
            @Param("propietarioId") UUID propietarioId, Pageable pagina);

    @Query(value = PROYECCION_FAMILIA + DEPORTISTA_FAMILIA + ORDEN, countQuery = "select count(doc)" + DEPORTISTA_FAMILIA)
    Page<DocumentoMetadatos> deportistaFamilia(@Param("escuelaId") UUID escuelaId,
            @Param("propietarioId") UUID propietarioId,
            @Param("familiaId") UUID familiaId, Pageable pagina);

    @Query(value = PROYECCION + TUTOR_FAMILIA + ORDEN, countQuery = "select count(doc)" + TUTOR_FAMILIA)
    Page<DocumentoMetadatos> tutorFamilia(@Param("escuelaId") UUID escuelaId,
            @Param("propietarioId") UUID propietarioId,
            @Param("familiaId") UUID familiaId, Pageable pagina);

    /** Los documentos vigentes de ciertos tipos de un deportista (checklist U20): mismo alcance que las consultas anteriores. */
    @Query(PROYECCION + DEPORTISTA + " and doc.tipo in :tipos" + ORDEN)
    List<DocumentoMetadatos> requisitosAdmin(@Param("escuelaId") UUID escuelaId,
            @Param("propietarioId") UUID propietarioId, @Param("tipos") Collection<TipoDocumento> tipos);

    @Query(PROYECCION_FAMILIA + DEPORTISTA_FAMILIA + " and doc.tipo in :tipos" + ORDEN)
    List<DocumentoMetadatos> requisitosFamilia(@Param("escuelaId") UUID escuelaId,
            @Param("propietarioId") UUID propietarioId, @Param("familiaId") UUID familiaId,
            @Param("tipos") Collection<TipoDocumento> tipos);
}
