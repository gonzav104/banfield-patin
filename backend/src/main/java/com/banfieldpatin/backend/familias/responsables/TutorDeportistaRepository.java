package com.banfieldpatin.backend.familias.responsables;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TutorDeportistaRepository extends JpaRepository<TutorDeportista, UUID> {

	String ALCANCE_ADMIN = """
			from TutorDeportista v
			join Tutor t on t.id = v.tutorId and t.escuelaId = v.escuelaId and t.familiaId = v.familiaId
			where v.escuelaId = :escuela and v.deportistaId = :deportista
			  and (:historial = true or v.cerradoEn is null)
			""";

	@Query(value = """
			select new com.banfieldpatin.backend.familias.responsables.VinculoTutorRespuesta(
			    v.id, v.familiaId, v.tutorId, t.nombre, t.apellido, v.tipo, v.creadoEn, v.cerradoEn,
			    v.causaCierre, v.version)
			""" + ALCANCE_ADMIN + " order by v.creadoEn desc, v.id desc",
			countQuery = "select count(v) " + ALCANCE_ADMIN)
	Page<VinculoTutorRespuesta> admin(@Param("escuela") UUID escuelaId, @Param("deportista") UUID deportistaId,
			@Param("historial") boolean historial, Pageable pagina);

	/** Proyección de una vigencia del deportista, abierta o cerrada, dentro de la escuela del actor. */
	@Query("""
			select new com.banfieldpatin.backend.familias.responsables.VinculoTutorRespuesta(
			    v.id, v.familiaId, v.tutorId, t.nombre, t.apellido, v.tipo, v.creadoEn, v.cerradoEn,
			    v.causaCierre, v.version)
			from TutorDeportista v
			join Tutor t on t.id = v.tutorId and t.escuelaId = v.escuelaId and t.familiaId = v.familiaId
			where v.escuelaId = :escuela and v.deportistaId = :deportista and v.id = :id
			""")
	Optional<VinculoTutorRespuesta> respuesta(@Param("escuela") UUID escuelaId, @Param("deportista") UUID deportistaId,
			@Param("id") UUID id);

	/** Existencia de una vigencia del deportista, sin cargar la entidad; la escuela acota la clave de idempotencia. */
	@Query("select count(v) > 0 from TutorDeportista v where v.escuelaId = :escuela and v.deportistaId = :deportista and v.id = :id")
	boolean existeDelDeportista(@Param("escuela") UUID escuelaId, @Param("deportista") UUID deportistaId,
			@Param("id") UUID id);

	@Query("select count(v) > 0 from TutorDeportista v where v.escuelaId = :escuela and v.solicitudId = :solicitud")
	boolean existeSolicitud(@Param("escuela") UUID escuelaId, @Param("solicitud") UUID solicitudId);
}
