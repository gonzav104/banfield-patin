package com.banfieldpatin.backend.familias.invitaciones;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface InvitacionRepository extends JpaRepository<Invitacion, UUID> {

	/** Toda lectura administrativa filtra por escuela: un id de otra escuela es indistinguible de uno inexistente. */
	Optional<Invitacion> findByIdAndEscuelaId(UUID id, UUID escuelaId);

	/** Reservado para el registro (PR5): SELECT ... FOR UPDATE serializa usos concurrentes del mismo token. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from Invitacion i where i.tokenHash = :tokenHash")
	Optional<Invitacion> findByTokenHashParaActualizar(@Param("tokenHash") String tokenHash);

	/**
	 * Listado de la escuela, mas recientes primero. {@code estado} es el nombre de un {@link EstadoInvitacion}
	 * o {@link #TODOS}; se usa un centinela (y no null) para no depender de la inferencia de tipo de un parametro nulo.
	 * Los predicados replican la precedencia de {@link EstadoInvitacion#desde}.
	 */
	@Query("""
			select i from Invitacion i
			where i.escuelaId = :escuelaId
			  and (:estado = 'TODOS'
			    or (:estado = 'USADA' and i.usadoEn is not null)
			    or (:estado = 'REVOCADA' and i.usadoEn is null and i.revocadaEn is not null)
			    or (:estado = 'EXPIRADA' and i.usadoEn is null and i.revocadaEn is null and i.expiraEn <= :ahora)
			    or (:estado = 'PENDIENTE' and i.usadoEn is null and i.revocadaEn is null and i.expiraEn > :ahora))
			order by i.creadoEn desc
			""")
	Page<Invitacion> listar(@Param("escuelaId") UUID escuelaId, @Param("estado") String estado,
			@Param("ahora") Instant ahora, Pageable pageable);

	String TODOS = "TODOS";

	/** Revocacion condicional: solo afecta a una fila aun no usada ni revocada de la escuela indicada. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Invitacion i set i.revocadaEn = :ahora, i.revocadaPor = :usuarioId
			where i.id = :id and i.escuelaId = :escuelaId and i.usadoEn is null and i.revocadaEn is null
			""")
	int marcarRevocada(@Param("id") UUID id, @Param("escuelaId") UUID escuelaId,
			@Param("usuarioId") UUID usuarioId, @Param("ahora") Instant ahora);
}
