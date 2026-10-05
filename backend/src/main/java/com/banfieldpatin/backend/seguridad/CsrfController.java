package com.banfieldpatin.backend.seguridad;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Parameter;

@RestController
public class CsrfController {

	public record CsrfRespuesta(String headerName, String token) {
	}

	/** Fuerza la emision de la cookie XSRF-TOKEN para que el SPA pueda enviar el header. */
	@GetMapping("/api/auth/csrf")
	CsrfRespuesta csrf(@Parameter(hidden = true) CsrfToken token) {
		return new CsrfRespuesta(token.getHeaderName(), token.getToken());
	}
}
