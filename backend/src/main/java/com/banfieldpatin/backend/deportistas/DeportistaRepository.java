package com.banfieldpatin.backend.deportistas;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/** Toda consulta lleva el predicado de escuela: un id de otra escuela es indistinguible de uno inexistente. */
public interface DeportistaRepository extends JpaRepository<Deportista, UUID> {

	Optional<Deportista> findByIdAndEscuelaId(UUID id, UUID escuelaId);

	/**
	 * Listado administrativo de la escuela. {@code estado} es el nombre de un {@code FiltroEstado} (centinela en lugar
	 * de un parametro nulo); {@code busqueda} llega con los comodines de LIKE escapados con '!' ("" = sin filtro) y se
	 * compara contra apellido, nombre, "nombre apellido" y "apellido nombre"; {@code dni} son los digitos buscados
	 * ("" = no buscar por DNI) y coincide por prefijo.
	 */
	@Query(value = """
			select d from Deportista d
			where d.escuelaId = :escuelaId
			  and (:estado = 'TODOS' or (:estado = 'ACTIVOS' and d.activo = true)
			    or (:estado = 'INACTIVOS' and d.activo = false))
			  and (:busqueda = ''
			    or lower(d.apellido) like lower(concat('%', :busqueda, '%')) escape '!'
			    or lower(d.nombre) like lower(concat('%', :busqueda, '%')) escape '!'
			    or lower(concat(d.nombre, ' ', d.apellido)) like lower(concat('%', :busqueda, '%')) escape '!'
			    or lower(concat(d.apellido, ' ', d.nombre)) like lower(concat('%', :busqueda, '%')) escape '!'
			    or (:dni <> '' and d.dni like concat(:dni, '%')))
			order by lower(d.apellido), lower(d.nombre), d.id
			""",
			countQuery = """
			select count(d) from Deportista d
			where d.escuelaId = :escuelaId
			  and (:estado = 'TODOS' or (:estado = 'ACTIVOS' and d.activo = true)
			    or (:estado = 'INACTIVOS' and d.activo = false))
			  and (:busqueda = ''
			    or lower(d.apellido) like lower(concat('%', :busqueda, '%')) escape '!'
			    or lower(d.nombre) like lower(concat('%', :busqueda, '%')) escape '!'
			    or lower(concat(d.nombre, ' ', d.apellido)) like lower(concat('%', :busqueda, '%')) escape '!'
			    or lower(concat(d.apellido, ' ', d.nombre)) like lower(concat('%', :busqueda, '%')) escape '!'
			    or (:dni <> '' and d.dni like concat(:dni, '%')))
			""")
	Page<Deportista> buscar(@Param("escuelaId") UUID escuelaId, @Param("estado") String estado,
			@Param("busqueda") String busqueda, @Param("dni") String dni, Pageable pageable);

	/**
	 * Estado del deportista de la escuela con ese DNI normalizado (vacio si no hay). El indice unico de DNI NO es parcial:
	 * un deportista inactivo sigue reservando su DNI.
	 */
	@Query("select d.activo from Deportista d where d.escuelaId = :escuelaId and d.dni = :dni")
	Optional<Boolean> activoPorDni(@Param("escuelaId") UUID escuelaId, @Param("dni") String dni);

	/** Igual que {@link #activoPorDni} pero ignora al propio deportista (edicion que conserva su DNI). */
	@Query("select d.activo from Deportista d where d.escuelaId = :escuelaId and d.dni = :dni and d.id <> :id")
	Optional<Boolean> activoPorDniDeOtro(@Param("escuelaId") UUID escuelaId, @Param("dni") String dni,
			@Param("id") UUID id);

	boolean existsByEscuelaIdAndCuil(UUID escuelaId, String cuil);

	boolean existsByEscuelaIdAndCuilAndIdNot(UUID escuelaId, String cuil, UUID id);

	/**
	 * Bloquea (FOR UPDATE) las filas de los deportistas de la escuela, siempre en orden de id para que dos vinculaciones
	 * concurrentes no se bloqueen entre si. Solo se declara aqui: la vinculacion que lo usa llega en el slice siguiente.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from Deportista d where d.escuelaId = :escuelaId and d.id in :ids order by d.id")
	List<Deportista> bloquearParaVincular(@Param("escuelaId") UUID escuelaId, @Param("ids") Collection<UUID> ids);
}
