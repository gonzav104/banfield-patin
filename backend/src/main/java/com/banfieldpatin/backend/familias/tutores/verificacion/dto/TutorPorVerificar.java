package com.banfieldpatin.backend.familias.tutores.verificacion.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Fila de la bandeja de verificación (ADMIN, U21-BIS C2): lo que la familia declaró, listo para verificar u observar sin
 * volver a tipearlo. ADMIN ve el DNI completo (como en la ficha del tutor). {@code dniFrente}/{@code dniDorso}: estado del
 * DNI vigente del tutor (nulo si falta); aprobar una imagen no verifica la identidad. {@code coincidenciasEnOtrasFamilias}:
 * adultos activos de OTRAS familias de la escuela con el mismo DNI (solo ADMIN lo ve; la familia nunca).
 */
public record TutorPorVerificar(UUID tutorId, UUID familiaId, String familiaNombre, String nombre, String apellido, String dni,
		String telefono, String email, String parentesco, String estado, String motivoObservacion, String origen,
		boolean tieneCuenta, boolean cuentaConfirmada, String dniFrente, String dniDorso, long coincidenciasEnOtrasFamilias,
		Instant declaradoEn, String version) {

	@Override
	public String toString() {
		return "TutorPorVerificar[tutorId=" + tutorId + "]";
	}
}
