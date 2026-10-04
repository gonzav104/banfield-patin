package com.banfieldpatin.backend.familias.invitaciones.dto;

import java.time.Instant;
import java.util.UUID;

import com.banfieldpatin.backend.familias.dto.FamiliaResumen;
import com.banfieldpatin.backend.familias.invitaciones.EstadoInvitacion;

/** Vista administrativa de una invitacion. Nunca incluye el token ni su hash. */
public record InvitacionRespuesta(
		UUID id,
		FamiliaResumen familia,
		String emailSugerido,
		EstadoInvitacion estado,
		Instant creadoEn,
		Instant expiraEn,
		Instant usadoEn,
		Instant revocadaEn) {
}
