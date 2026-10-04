package com.banfieldpatin.backend.familias.invitaciones;

import java.time.Instant;

/**
 * Estado derivado (no se guarda). Precedencia: USADA, REVOCADA, EXPIRADA, PENDIENTE.
 * Una invitacion usada o revocada conserva ese estado aunque tambien haya vencido.
 */
public enum EstadoInvitacion {
	PENDIENTE,
	USADA,
	REVOCADA,
	EXPIRADA;

	public static EstadoInvitacion desde(Invitacion invitacion, Instant ahora) {
		if (invitacion.getUsadoEn() != null) {
			return USADA;
		}
		if (invitacion.getRevocadaEn() != null) {
			return REVOCADA;
		}
		if (!invitacion.getExpiraEn().isAfter(ahora)) {
			return EXPIRADA;
		}
		return PENDIENTE;
	}
}
