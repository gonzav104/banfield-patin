package com.banfieldpatin.backend.temporadas;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Toda consulta lleva el predicado de escuela: una fila de otra escuela es indistinguible de una inexistente. */
public interface DeportistaTemporadaRepository extends JpaRepository<DeportistaTemporada, UUID> {

	Optional<DeportistaTemporada> findByEscuelaIdAndDeportistaIdAndTemporadaId(UUID escuelaId, UUID deportistaId,
			UUID temporadaId);

	boolean existsByEscuelaIdAndDeportistaIdAndTemporadaId(UUID escuelaId, UUID deportistaId, UUID temporadaId);

	boolean existsByEscuelaIdAndTemporadaIdAndNumeroCorredor(UUID escuelaId, UUID temporadaId, String numeroCorredor);

	/** Igual que la anterior pero ignora a la propia fila (edicion que conserva su numero). */
	boolean existsByEscuelaIdAndTemporadaIdAndNumeroCorredorAndIdNot(UUID escuelaId, UUID temporadaId,
			String numeroCorredor, UUID id);

	/** Historial de un deportista, del anio mas nuevo al mas viejo, en UNA consulta (el anio sale del join). */
	@Query("""
			select new com.banfieldpatin.backend.temporadas.FilaDeportistaTemporada(dt.id, dt.deportistaId,
			    dt.temporadaId, t.anio, dt.categoria, dt.rama, dt.divisional, dt.numeroCorredor, dt.estadoDeportivo)
			from DeportistaTemporada dt join Temporada t on t.id = dt.temporadaId
			where dt.escuelaId = :escuelaId and dt.deportistaId = :deportistaId
			order by t.anio desc, dt.id
			""")
	List<FilaDeportistaTemporada> historial(@Param("escuelaId") UUID escuelaId, @Param("deportistaId") UUID deportistaId);

	@Query("""
			select new com.banfieldpatin.backend.temporadas.FilaDeportistaTemporada(dt.id, dt.deportistaId,
			    dt.temporadaId, t.anio, dt.categoria, dt.rama, dt.divisional, dt.numeroCorredor, dt.estadoDeportivo)
			from DeportistaTemporada dt join Temporada t on t.id = dt.temporadaId
			where dt.escuelaId = :escuelaId and dt.deportistaId = :deportistaId and dt.temporadaId = :temporadaId
			""")
	Optional<FilaDeportistaTemporada> fila(@Param("escuelaId") UUID escuelaId, @Param("deportistaId") UUID deportistaId,
			@Param("temporadaId") UUID temporadaId);

	/**
	 * Plantel de una temporada, por apellido y nombre, en una consulta mas su conteo (sin N+1). {@code filtro} es "TODOS" o
	 * el nombre de un {@link EstadoDeportivo} (centinela en lugar de un parametro nulo); {@code estado} lleva el valor ya
	 * convertido (cualquiera si el filtro es "TODOS").
	 */
	@Query(value = """
			select new com.banfieldpatin.backend.temporadas.FilaPlantel(dt.id, dt.deportistaId, dt.temporadaId,
			    d.nombre, d.apellido, dt.categoria, dt.rama, dt.divisional, dt.numeroCorredor, dt.estadoDeportivo)
			from DeportistaTemporada dt join Deportista d on d.id = dt.deportistaId
			where dt.escuelaId = :escuelaId and dt.temporadaId = :temporadaId
			  and (:filtro = 'TODOS' or dt.estadoDeportivo = :estado)
			order by lower(d.apellido), lower(d.nombre), dt.id
			""",
			countQuery = """
			select count(dt) from DeportistaTemporada dt
			where dt.escuelaId = :escuelaId and dt.temporadaId = :temporadaId
			  and (:filtro = 'TODOS' or dt.estadoDeportivo = :estado)
			""")
	Page<FilaPlantel> plantel(@Param("escuelaId") UUID escuelaId, @Param("temporadaId") UUID temporadaId,
			@Param("filtro") String filtro, @Param("estado") EstadoDeportivo estado, Pageable pageable);

	/**
	 * Serializa las escrituras sobre ESA fila con {@code FOR NO KEY UPDATE} (no choca con el {@code FOR KEY SHARE} de las
	 * claves foraneas). Es nativa a proposito: el SQL que se ejecuta es exactamente este. Vacio si la fila no existe.
	 */
	@Query(value = """
			select dt.id from gestion_patin.deportista_temporada dt
			where dt.escuela_id = :escuelaId and dt.deportista_id = :deportistaId and dt.temporada_id = :temporadaId
			for no key update
			""", nativeQuery = true)
	Optional<UUID> bloquear(@Param("escuelaId") UUID escuelaId, @Param("deportistaId") UUID deportistaId,
			@Param("temporadaId") UUID temporadaId);
}
