package com.banfieldpatin.backend.seguridad;

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
	boolean vigente(UUID usuarioId, UUID escuelaId, String rol, UUID familiaId);
}
