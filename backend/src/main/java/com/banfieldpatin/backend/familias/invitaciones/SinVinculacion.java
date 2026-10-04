package com.banfieldpatin.backend.familias.invitaciones;

import com.banfieldpatin.backend.usuarios.Usuario;

/**
 * Implementacion por defecto del punto de extension: no escribe nada (este cambio no toca familia_deportista).
 * No es un bean: {@link RegistroPorInvitacionService} la usa solo cuando no existe otra implementacion registrada.
 */
public class SinVinculacion implements VinculacionPorInvitacion {

	@Override
	public void alRegistrar(Invitacion invitacion, Usuario usuario) {
		// Sin vinculacion: el deportista de la invitacion (si lo hubiera) se asocia en un cambio posterior.
	}
}
