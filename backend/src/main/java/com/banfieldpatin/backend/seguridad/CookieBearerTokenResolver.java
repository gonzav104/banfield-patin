package com.banfieldpatin.backend.seguridad;

import java.util.Set;

import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Lee el token solo desde la cookie de sesion; ignora el header Authorization.
 * En rutas publicas de autenticacion devuelve null para que una cookie vencida no convierta un login en 401.
 * Logout NO es publico: exige sesion valida.
 */
@Component
public class CookieBearerTokenResolver implements BearerTokenResolver {

	static final Set<String> RUTAS_PUBLICAS = Set.of(
			"/api/auth/login",
			"/api/auth/admin/login",
			"/api/auth/csrf",
			"/api/auth/invitaciones/validar",
			"/api/auth/registro/invitacion");

	private final CookieSesion cookieSesion;

	public CookieBearerTokenResolver(CookieSesion cookieSesion) {
		this.cookieSesion = cookieSesion;
	}

	@Override
	public String resolve(HttpServletRequest request) {
		String ruta = request.getRequestURI().substring(request.getContextPath().length());
		if (RUTAS_PUBLICAS.contains(ruta)) {
			return null;
		}
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (cookieSesion.nombre().equals(cookie.getName()) && !cookie.getValue().isBlank()) {
				return cookie.getValue();
			}
		}
		return null;
	}
}
