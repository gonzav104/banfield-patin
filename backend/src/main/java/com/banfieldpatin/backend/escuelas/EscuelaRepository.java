package com.banfieldpatin.backend.escuelas;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface EscuelaRepository extends JpaRepository<Escuela, UUID> {

	/** Usa lower() para coincidir con el indice unico funcional uq_escuela_slug. */
	@Query("select e from Escuela e where lower(e.slug) = lower(:slug)")
	Optional<Escuela> findBySlugIgnoreCase(@Param("slug") String slug);

	/** Bloqueo pesimista: serializa el conteo de administradores del bootstrap entre instancias. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Escuela e where lower(e.slug) = lower(:slug)")
	Optional<Escuela> findBySlugParaActualizar(@Param("slug") String slug);
}
