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

	/**
	 * Listado administrativo de la escuela. {@code estado} es el nombre de un {@code FiltroEstado}
	 * (TODOS, ACTIVOS o INACTIVOS; centinela en lugar de un parametro nulo) y {@code busqueda} llega con los comodines
	 * de LIKE escapados con '!' ("" = sin filtro).
	 */
	@Query(value = """
			select f from Familia f
			where f.escuelaId = :escuelaId
			  and (:estado = 'TODOS' or (:estado = 'ACTIVOS' and f.activa = true)
			    or (:estado = 'INACTIVOS' and f.activa = false))
			  and (:busqueda = '' or lower(f.nombreReferencia) like lower(concat('%', :busqueda, '%')) escape '!')
			order by lower(f.nombreReferencia), f.id
			""",
			countQuery = """
			select count(f) from Familia f
			where f.escuelaId = :escuelaId
			  and (:estado = 'TODOS' or (:estado = 'ACTIVOS' and f.activa = true)
			    or (:estado = 'INACTIVOS' and f.activa = false))
			  and (:busqueda = '' or lower(f.nombreReferencia) like lower(concat('%', :busqueda, '%')) escape '!')
			""")
	Page<Familia> buscar(@Param("escuelaId") UUID escuelaId, @Param("estado") String estado,
			@Param("busqueda") String busqueda, Pageable pageable);
}
