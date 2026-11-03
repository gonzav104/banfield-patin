package com.banfieldpatin.backend.documentos;
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
    String PROYECCION = """
 select new com.banfieldpatin.backend.documentos.DocumentoMetadatos(
 doc.id, doc.tipo, doc.estado, doc.temporadaId, doc.creadoEn, doc.vigente, doc.fechaVencimiento,
 case when doc.estado = com.banfieldpatin.backend.documentos.EstadoDocumento.OBSERVADO
 then doc.motivoObservacion else null end)
 """;
    String ORDEN = " order by doc.creadoEn desc, doc.id";
    String DEPORTISTA = " from Documento doc where doc.escuelaId = :escuelaId and doc.deportistaId = :propietarioId and doc.vigente = true";
    String TUTOR = " from Documento doc where doc.escuelaId = :escuelaId and doc.tutorId = :propietarioId and doc.vigente = true";
    String DEPORTISTA_FAMILIA = DEPORTISTA + " and exists (select fd.id " + FamiliaDeportistaRepository.ALCANCE_FAMILIA + " and d.id = doc.deportistaId)";
    String TUTOR_FAMILIA = TUTOR + " and exists (select t.id " + TutorRepository.ALCANCE_FAMILIA + " and t.id = doc.tutorId)";

    @Query(value = PROYECCION + DEPORTISTA + ORDEN, countQuery = "select count(doc)" + DEPORTISTA)
    Page<DocumentoMetadatos> deportistaAdmin(@Param("escuelaId") UUID escuelaId,
            @Param("propietarioId") UUID propietarioId, Pageable pagina);

    @Query(value = PROYECCION + TUTOR + ORDEN, countQuery = "select count(doc)" + TUTOR)
    Page<DocumentoMetadatos> tutorAdmin(@Param("escuelaId") UUID escuelaId,
            @Param("propietarioId") UUID propietarioId, Pageable pagina);

    @Query(value = PROYECCION + DEPORTISTA_FAMILIA + ORDEN, countQuery = "select count(doc)" + DEPORTISTA_FAMILIA)
    Page<DocumentoMetadatos> deportistaFamilia(@Param("escuelaId") UUID escuelaId,
            @Param("propietarioId") UUID propietarioId,
            @Param("familiaId") UUID familiaId, Pageable pagina);

    @Query(value = PROYECCION + TUTOR_FAMILIA + ORDEN, countQuery = "select count(doc)" + TUTOR_FAMILIA)
    Page<DocumentoMetadatos> tutorFamilia(@Param("escuelaId") UUID escuelaId,
            @Param("propietarioId") UUID propietarioId,
            @Param("familiaId") UUID familiaId, Pageable pagina);
}
