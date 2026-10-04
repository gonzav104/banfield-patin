package com.banfieldpatin.backend.usuarios.mfa;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Todas las escrituras son condicionales y atomicas: el resultado indica si la fila cambio (1) o no (0). */
public interface UsuarioMfaRepository extends JpaRepository<UsuarioMfa, UUID> {

	/**
	 * Crea el enrolamiento o reemplaza el secreto de uno aun SIN confirmar (idempotente antes de confirmar). Devuelve
	 * 0 si el factor ya esta confirmado: un enrolamiento confirmado no se reemplaza, solo se reinicia.
	 */
	@Modifying(flushAutomatically = true)
	@Query(nativeQuery = true, value = """
			INSERT INTO gestion_patin.usuario_mfa AS m (usuario_id, escuela_id, secreto_cifrado)
			VALUES (:usuarioId, :escuelaId, :secretoCifrado)
			ON CONFLICT (usuario_id) DO UPDATE
			    SET secreto_cifrado = EXCLUDED.secreto_cifrado, ultimo_paso_usado = NULL
			    WHERE m.confirmado_en IS NULL
			""")
	int guardarEnrolamiento(@Param("usuarioId") UUID usuarioId, @Param("escuelaId") UUID escuelaId,
			@Param("secretoCifrado") byte[] secretoCifrado);

	/** Confirma el enrolamiento y registra el paso del primer codigo. 0 filas = ya confirmado o inexistente. */
	@Modifying(flushAutomatically = true)
	@Query("""
			update UsuarioMfa m set m.confirmadoEn = :ahora, m.ultimoPasoUsado = :paso
			where m.usuarioId = :usuarioId and m.confirmadoEn is null
			""")
	int confirmar(@Param("usuarioId") UUID usuarioId, @Param("paso") long paso, @Param("ahora") Instant ahora);

	/**
	 * Anti-repeticion: acepta el paso solo si es mayor que el ultimo usado, en una sola sentencia atomica (dos
	 * peticiones concurrentes con el mismo codigo: solo una afecta la fila). 0 filas = repetido o no confirmado.
	 */
	@Modifying(flushAutomatically = true)
	@Query("""
			update UsuarioMfa m set m.ultimoPasoUsado = :paso
			where m.usuarioId = :usuarioId and m.confirmadoEn is not null
			  and (m.ultimoPasoUsado is null or m.ultimoPasoUsado < :paso)
			""")
	int consumirPaso(@Param("usuarioId") UUID usuarioId, @Param("paso") long paso);

	@Modifying(flushAutomatically = true)
	@Query("delete from UsuarioMfa m where m.usuarioId = :usuarioId")
	int eliminar(@Param("usuarioId") UUID usuarioId);
}
