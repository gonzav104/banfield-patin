package com.banfieldpatin.backend.escuelas;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EscuelaRepository extends JpaRepository<Escuela, UUID> {

	/** Usa lower() para coincidir con el indice unico funcional uq_escuela_slug. */
	@Query("select e from Escuela e where lower(e.slug) = lower(:slug)")
	Optional<Escuela> findBySlugIgnoreCase(@Param("slug") String slug);
}
