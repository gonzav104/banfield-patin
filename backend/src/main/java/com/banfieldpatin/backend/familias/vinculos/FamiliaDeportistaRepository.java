package com.banfieldpatin.backend.familias.vinculos;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta;

/**
 * Toda consulta lleva el predicado de escuela y los joins con Familia/Deportista comparan id Y escuela: un id de otra
 * escuela es indistinguible de uno inexistente. Los literales de estado usan el nombre completo del enum (Hibernate 7.4
 * lo acepta; lo fija MapeoVinculosDbTest).
 */
public interface FamiliaDeportistaRepository extends JpaRepository<FamiliaDeportista, UUID> {

	String PROYECCION = """
			select new com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta(
			    fd.id, f.id, f.nombreReferencia, d.id, d.nombre, d.apellido, d.activo, fd.estado, fd.esPrincipal,
			    fd.autorizadoEn)
			from FamiliaDeportista fd
			join Familia f on f.id = fd.familiaId and f.escuelaId = fd.escuelaId
			join Deportista d on d.id = fd.deportistaId and d.escuelaId = fd.escuelaId
			""";

	/** Vinculos (cualquier estado) de la familia con esos deportistas: UNA consulta para todo el lote. */
	@Query("""
			select fd from FamiliaDeportista fd
			where fd.escuelaId = :escuelaId and fd.familiaId = :familiaId and fd.deportistaId in :deportistaIds
			""")
	List<FamiliaDeportista> deFamiliaYDeportistas(@Param("escuelaId") UUID escuelaId,
			@Param("familiaId") UUID familiaId, @Param("deportistaIds") Collection<UUID> deportistaIds);

	@Query("""
			select fd from FamiliaDeportista fd
			where fd.escuelaId = :escuelaId and fd.familiaId = :familiaId and fd.deportistaId = :deportistaId
			""")
	Optional<FamiliaDeportista> buscarVinculo(@Param("escuelaId") UUID escuelaId, @Param("familiaId") UUID familiaId,
			@Param("deportistaId") UUID deportistaId);

	/** El vinculo ACTIVO principal del deportista (a lo sumo uno: uq_fd_principal_activo). */
	@Query("""
			select fd from FamiliaDeportista fd
			where fd.escuelaId = :escuelaId and fd.deportistaId = :deportistaId
			  and fd.estado = com.banfieldpatin.backend.familias.vinculos.EstadoVinculo.ACTIVO
			  and fd.esPrincipal = true
			""")
	Optional<FamiliaDeportista> principalActivo(@Param("escuelaId") UUID escuelaId,
			@Param("deportistaId") UUID deportistaId);

	/** Ids de los deportistas (de la lista) que ya tienen un vinculo ACTIVO principal: UNA consulta para todo el lote. */
	@Query("""
			select fd.deportistaId from FamiliaDeportista fd
			where fd.escuelaId = :escuelaId and fd.deportistaId in :deportistaIds
			  and fd.estado = com.banfieldpatin.backend.familias.vinculos.EstadoVinculo.ACTIVO
			  and fd.esPrincipal = true
			""")
	List<UUID> principalesActivos(@Param("escuelaId") UUID escuelaId,
			@Param("deportistaIds") Collection<UUID> deportistaIds);

	/**
	 * Cantidad de deportistas ACTIVOS vinculados (vinculo ACTIVO) por familia en UNA consulta agrupada: los vinculos no
	 * ACTIVOS y los deportistas inactivos no cuentan (las familias sin ninguno no aparecen).
	 */
	@Query("""
			select new com.banfieldpatin.backend.familias.vinculos.ConteoVinculos(fd.familiaId, count(fd))
			from FamiliaDeportista fd
			join Deportista d on d.id = fd.deportistaId and d.escuelaId = fd.escuelaId
			where fd.escuelaId = :escuelaId and fd.familiaId in :familiaIds
			  and fd.estado = com.banfieldpatin.backend.familias.vinculos.EstadoVinculo.ACTIVO
			  and d.activo = true
			group by fd.familiaId
			""")
	List<ConteoVinculos> contarActivosPorFamilia(@Param("escuelaId") UUID escuelaId,
			@Param("familiaIds") Collection<UUID> familiaIds);

	/** Todos los vinculos de la familia (cualquier estado), por apellido, nombre e id del deportista. */
	@Query(PROYECCION + """
			where fd.escuelaId = :escuelaId and fd.familiaId = :familiaId
			order by lower(d.apellido), lower(d.nombre), d.id
			""")
	List<VinculoRespuesta> deFamilia(@Param("escuelaId") UUID escuelaId, @Param("familiaId") UUID familiaId);

	/** Todos los vinculos del deportista (cualquier estado), por nombre de familia e id. */
	@Query(PROYECCION + """
			where fd.escuelaId = :escuelaId and fd.deportistaId = :deportistaId
			order by lower(f.nombreReferencia), f.id
			""")
	List<VinculoRespuesta> deDeportista(@Param("escuelaId") UUID escuelaId, @Param("deportistaId") UUID deportistaId);

	@Query(PROYECCION + """
			where fd.escuelaId = :escuelaId and fd.familiaId = :familiaId and fd.deportistaId = :deportistaId
			""")
	Optional<VinculoRespuesta> respuesta(@Param("escuelaId") UUID escuelaId, @Param("familiaId") UUID familiaId,
			@Param("deportistaId") UUID deportistaId);

	/**
	 * Revocacion condicional: solo cambia un vinculo que SIGUE ACTIVO (devuelve las filas cambiadas: 1 o 0) y le quita el
	 * caracter principal. Vuelca antes los cambios pendientes y limpia el contexto de persistencia despues (la
	 * actualizacion masiva no pasa por las entidades gestionadas).
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update FamiliaDeportista fd
			set fd.estado = com.banfieldpatin.backend.familias.vinculos.EstadoVinculo.REVOCADO, fd.esPrincipal = false
			where fd.escuelaId = :escuelaId and fd.familiaId = :familiaId and fd.deportistaId = :deportistaId
			  and fd.estado = com.banfieldpatin.backend.familias.vinculos.EstadoVinculo.ACTIVO
			""")
	int revocarSiActivo(@Param("escuelaId") UUID escuelaId, @Param("familiaId") UUID familiaId,
			@Param("deportistaId") UUID deportistaId);
}
