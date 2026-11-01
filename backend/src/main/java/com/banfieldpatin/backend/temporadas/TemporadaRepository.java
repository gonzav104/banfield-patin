package com.banfieldpatin.backend.temporadas;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TemporadaRepository extends JpaRepository<Temporada, UUID> {

	Optional<Temporada> findByIdAndEscuelaId(UUID id, UUID escuelaId);

	boolean existsByEscuelaIdAndAnio(UUID escuelaId, Short anio);

	/** Lectura escalar (sin entidad gestionada): ver {@link TemporadaVista}. */
	@Query("""
			select new com.banfieldpatin.backend.temporadas.TemporadaVista(t.id, t.anio, t.activa)
			from Temporada t where t.id = :id and t.escuelaId = :escuelaId
			""")
	Optional<TemporadaVista> vistaDe(@Param("id") UUID id, @Param("escuelaId") UUID escuelaId);

	/** La temporada activa de la escuela (a lo sumo una: lo garantiza uq_temporada_activa_por_escuela). */
	@Query("select t from Temporada t where t.escuelaId = :escuelaId and t.activa = true")
	Optional<Temporada> activaDe(@Param("escuelaId") UUID escuelaId);

	@Query(value = "select t from Temporada t where t.escuelaId = :escuelaId order by t.anio desc, t.id",
			countQuery = "select count(t) from Temporada t where t.escuelaId = :escuelaId")
	Page<Temporada> listar(@Param("escuelaId") UUID escuelaId, Pageable pageable);
}
