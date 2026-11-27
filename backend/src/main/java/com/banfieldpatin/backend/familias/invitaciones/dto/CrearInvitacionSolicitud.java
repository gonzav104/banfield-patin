package com.banfieldpatin.backend.familias.invitaciones.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Exactamente una de familiaId / nuevaFamilia. {@code tutorId} (opcional, solo con familiaId): ADMIN invita a ese tutor de la familia
 * y la cuenta que se registre nace vinculada y verificada a esa persona. La escuela y el creador salen del JWT, no de este cuerpo.
 * Cualquier propiedad desconocida (en particular deportistaId, que ninguna API acepta todavia) se rechaza con
 * 400 SOLICITUD_INVALIDA: el any-setter lanza y Jackson lo reporta como cuerpo ilegible, sin eco del valor.
 */
@UnaFamilia
public record CrearInvitacionSolicitud(
		UUID familiaId,
		@Valid NuevaFamiliaSolicitud nuevaFamilia,
		@Email @Size(max = 180) String emailSugerido,
		UUID tutorId,
		@Min(1) Integer diasVigencia) {

	/** Invitación sin tutor concreto (el caso de siempre). */
	public CrearInvitacionSolicitud(UUID familiaId, NuevaFamiliaSolicitud nuevaFamilia, String emailSugerido,
			Integer diasVigencia) {
		this(familiaId, nuevaFamilia, emailSugerido, null, diasVigencia);
	}

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}
}
