package com.banfieldpatin.backend.familias.responsables;

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
}
