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

	/**
	 * Serializa SOLO las activaciones de temporada de esa escuela con {@code FOR NO KEY UPDATE}. A diferencia de
	 * {@code FOR UPDATE} no choca con el {@code FOR KEY SHARE} que PostgreSQL toma sobre esta fila al comprobar toda clave
	 * foranea hacia {@code escuela} (familias, auditoria, ...): esas operaciones de la misma escuela no esperan. Es nativa
	 * a proposito: el SQL que se ejecuta es exactamente este y no depende del dialecto. Vacio si la escuela no existe.
	 */
	@Query(value = "select e.id from gestion_patin.escuela e where e.id = :id for no key update", nativeQuery = true)
	Optional<UUID> bloquearParaActivarTemporada(@Param("id") UUID id);
}
