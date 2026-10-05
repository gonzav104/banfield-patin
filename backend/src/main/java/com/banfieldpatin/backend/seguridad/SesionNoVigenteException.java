package com.banfieldpatin.backend.seguridad;

import org.springframework.security.core.AuthenticationException;

/**
 * El JWT es valido criptograficamente pero la sesion ya no lo es en la base (usuario, escuela o familia inactivos o
 * inexistentes, o rol/familia que no coinciden). El mensaje es generico: nunca lleva ids ni el motivo.
 */
public class SesionNoVigenteException extends AuthenticationException {

	public SesionNoVigenteException(String mensaje) {
		super(mensaje);
	}

	public SesionNoVigenteException(String mensaje, Throwable causa) {
		super(mensaje, causa);
	}
}
