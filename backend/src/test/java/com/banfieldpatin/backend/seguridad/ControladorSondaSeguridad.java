package com.banfieldpatin.backend.seguridad;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints falsos solo para probar la matriz de seguridad. */
@RestController
@Profile("!e2e")
class ControladorSondaSeguridad {

	static final AtomicInteger INVOCACIONES = new AtomicInteger();

	@GetMapping("/api/admin/ping")
	String admin() {
		return "admin";
	}

	@GetMapping("/api/familia/ping")
	String familia() {
		return "familia";
	}

	@GetMapping("/api/auth/me")
	String me() {
		return "me";
	}

	/** Ruta de segundo factor: solo debe responder con un token de MFA pendiente. */
	@PostMapping("/api/auth/admin/mfa/ping")
	String mfa() {
		INVOCACIONES.incrementAndGet();
		return "mfa";
	}

	/** Ruta fuera de /api/admin y /api/familia: cae en la regla "anyRequest" de la cadena. */
	@GetMapping("/api/otra/ruta")
	String otra() {
		return "otra";
	}

	@PostMapping("/api/auth/login")
	String login() {
		INVOCACIONES.incrementAndGet();
		return "login";
	}

	@PostMapping("/api/auth/logout")
	ResponseEntity<Void> logout() {
		INVOCACIONES.incrementAndGet();
		return ResponseEntity.noContent().build();
	}
}
