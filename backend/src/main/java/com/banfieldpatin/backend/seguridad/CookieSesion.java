package com.banfieldpatin.backend.seguridad;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Construye la cookie de sesion (HttpOnly) y su version de expiracion con los mismos atributos. */
@Component
public class CookieSesion {

	private final SeguridadPropiedades propiedades;

	public CookieSesion(SeguridadPropiedades propiedades) {
		this.propiedades = propiedades;
	}

	public String nombre() {
		return propiedades.cookie().nombre();
	}

	public ResponseCookie crear(String token) {
		return base(token).maxAge(propiedades.jwt().duracion()).build();
	}

	/** Cookie del token con MFA pendiente: su vida es la del token (corta), no la de una sesion completa. */
	public ResponseCookie crearMfaPendiente(String token) {
		return base(token).maxAge(propiedades.mfa().duracionPendiente()).build();
	}

	public ResponseCookie borrar() {
		return base("").maxAge(0).build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String valor) {
		var cookie = propiedades.cookie();
		return ResponseCookie.from(cookie.nombre(), valor)
				.httpOnly(true)
				.secure(cookie.secure())
				.sameSite(cookie.sameSite())
				.path("/");
	}
}
