package com.banfieldpatin.backend.familias.tutores;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Toda consulta lleva el predicado de escuela: un id de otra escuela es indistinguible de uno inexistente. */
public interface TutorRepository extends JpaRepository<Tutor, UUID> {

	Optional<Tutor> findByIdAndEscuelaId(UUID id, UUID escuelaId);

	/** Tutores de la familia (cualquier estado) ordenados por apellido, nombre y id. */
	@Query("""
			select t from Tutor t
			where t.escuelaId = :escuelaId and t.familiaId = :familiaId
			order by lower(t.apellido), lower(t.nombre), t.id
			""")
	List<Tutor> deFamilia(@Param("escuelaId") UUID escuelaId, @Param("familiaId") UUID familiaId);

	/** Solo los tutores activos de la familia (los usara el portal de FAMILIA); mismo orden que {@link #deFamilia}. */
	@Query("""
			select t from Tutor t
			where t.escuelaId = :escuelaId and t.familiaId = :familiaId and t.activo = true
			order by lower(t.apellido), lower(t.nombre), t.id
			""")
	List<Tutor> activosDeFamilia(@Param("escuelaId") UUID escuelaId, @Param("familiaId") UUID familiaId);

	/**
	 * Cantidad de tutores por familia en UNA sola consulta agrupada (las familias sin tutores no aparecen). Es la base
	 * del listado administrativo sin N+1.
	 */
	@Query("""
			select new com.banfieldpatin.backend.familias.tutores.ConteoTutores(t.familiaId, count(t))
			from Tutor t
			where t.escuelaId = :escuelaId and t.familiaId in :familiaIds
			group by t.familiaId
			""")
	List<ConteoTutores> contarPorFamilia(@Param("escuelaId") UUID escuelaId,
			@Param("familiaIds") Collection<UUID> familiaIds);
}
