package com.banfieldpatin.backend.seguridad;

import java.time.Instant;
import java.util.UUID;

/**
 * Puerto de la revalidacion central de la sesion (REQ-XC-09): se consulta en CADA solicitud autenticada, sin cache.
 * <p>
 * Una sesion es vigente solo si, en la base, el usuario existe y esta activo, su escuela esta activa, el {@code rol} y
 * el {@code familia_id} del token coinciden con la fila del usuario y, para un token de FAMILIA, la familia existe en
 * esa escuela y esta activa.
 * <p>
 * Implementaciones: {@code false} significa sesion muerta (401 y cookie borrada). Una falla de infraestructura debe
 * propagarse como {@link org.springframework.dao.DataAccessException} (503, cookie intacta); nunca devolver
 * {@code true} ante un error: el sistema falla cerrado.
 */
@FunctionalInterface
public interface VerificadorSesionVigente {

	/**
	 * @param usuarioId claim {@code sub}
	 * @param escuelaId claim {@code escuela_id}
	 * @param rol       claim {@code rol} ({@code ADMIN} o {@code FAMILIA})
	 * @param familiaId claim {@code familia_id}; {@code null} para ADMIN
	 */
	/**
	 * Garantía MÁS DÉBIL que {@link #vigente(UUID, UUID, String, UUID, Instant)}: no compara la emisión del token con la
	 * última reactivación (V19). Solo para revalidar DENTRO de una solicitud cuyo token ya pasó la comprobación completa
	 * al entrar (por ejemplo, la carga documental antes de publicar); nunca como verificación de un token.
	 */
	default boolean vigenteSinComprobarEmision(UUID usuarioId, UUID escuelaId, String rol, UUID familiaId) {
		return vigente(usuarioId, escuelaId, rol, familiaId, null);
	}

	/**
	 * Revalidación completa de un token: usuario, escuela y familia activos, reclamos coherentes y, rechaza un token emitido antes de la última
	 * reactivación de la cuenta (reactivar no revive las sesiones anteriores a la desactivación).
	 *
	 * @param emitidoEn claim {@code iat}; {@code null} solo cuando no hay token a mano (revalidación dentro de una
	 *                  operación ya autenticada al empezar la solicitud) y entonces no se compara la emisión
	 */
	boolean vigente(UUID usuarioId, UUID escuelaId, String rol, UUID familiaId, Instant emitidoEn);
}
