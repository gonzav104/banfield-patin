package com.banfieldpatin.backend.familias.invitaciones;

import com.banfieldpatin.backend.usuarios.Usuario;

/**
 * Punto de extension de RF-04: se invoca una vez por registro exitoso, dentro de la misma transaccion, con la
 * invitacion consumida y el usuario recien creado. La implementacion por defecto ({@link SinVinculacion}) no hace nada;
 * un modulo futuro puede aportar otra para vincular el deportista de la invitacion con la familia.
 */
public interface VinculacionPorInvitacion {

	void alRegistrar(Invitacion invitacion, Usuario usuario);
}
