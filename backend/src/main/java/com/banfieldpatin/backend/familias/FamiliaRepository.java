package com.banfieldpatin.backend.familias;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamiliaRepository extends JpaRepository<Familia, UUID> {

	Optional<Familia> findByIdAndEscuelaId(UUID id, UUID escuelaId);

	/**
	 * Familias activas de la escuela cuyo nombre contiene {@code busqueda} (sin distinguir mayusculas).
	 * {@code busqueda} llega con los comodines de LIKE ya escapados con '!' ("" = sin filtro).
	 */
	@Query("""
			select f from Familia f
			where f.escuelaId = :escuelaId and f.activa = true
			  and (:busqueda = '' or lower(f.nombreReferencia) like lower(concat('%', :busqueda, '%')) escape '!')
			order by lower(f.nombreReferencia), f.id
			""")
	Page<Familia> buscarActivas(@Param("escuelaId") UUID escuelaId, @Param("busqueda") String busqueda,
			Pageable pageable);
}
