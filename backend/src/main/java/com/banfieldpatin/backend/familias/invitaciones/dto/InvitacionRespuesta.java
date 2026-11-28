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
		Instant revocadaEn,
		UUID tutorId,
		String asociacionDePersona) {

	/** Invitación sin persona concreta (la de siempre): sin tutor y sin estado de asociación. */
	public InvitacionRespuesta(UUID id, FamiliaResumen familia, String emailSugerido, EstadoInvitacion estado, Instant creadoEn,
			Instant expiraEn, Instant usadoEn, Instant revocadaEn) {
		this(id, familia, emailSugerido, estado, creadoEn, expiraEn, usadoEn, revocadaEn, null, null);
	}

	/** La invitación nombra a una persona y todavía no se usó. */
	public static final String PENDIENTE = "PENDIENTE";
	/** Se usó y la cuenta quedó asociada a la persona invitada (verificada por la decisión de ADMIN al invitar). */
	public static final String ASOCIADA = "ASOCIADA";
	/** Se usó pero la persona ya había sido reclamada por otra cuenta: la cuenta se registró SIN asociación. */
	public static final String NO_ASOCIADA = "NO_ASOCIADA";
}
