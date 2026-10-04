package com.banfieldpatin.backend.familias.invitaciones.dto;

import java.time.Instant;
import java.util.UUID;

import com.banfieldpatin.backend.familias.dto.FamiliaResumen;
import com.banfieldpatin.backend.familias.invitaciones.EstadoInvitacion;

/**
 * Unica respuesta que contiene el token en claro (se muestra una sola vez; solo se guarda su hash).
 * toString lo oculta para que no termine en logs.
 */
public record InvitacionCreadaRespuesta(
		UUID id,
		EstadoInvitacion estado,
		String token,
		String enlaceRegistro,
		Instant expiraEn,
		FamiliaResumen familia,
		String emailSugerido) {

	@Override
	public String toString() {
		return "InvitacionCreadaRespuesta[id=" + id + ", estado=" + estado + ", token=***]";
	}
}
