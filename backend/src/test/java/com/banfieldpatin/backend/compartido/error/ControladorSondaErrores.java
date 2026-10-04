package com.banfieldpatin.backend.compartido.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Controlador solo de pruebas para ejercitar el manejador global. */
@RestController
@Profile("!e2e")
class ControladorSondaErrores {

	record Entrada(@NotBlank String nombre, @Size(min = 10) String password) {
	}

	@PostMapping("/sonda/validar")
	String validar(@Valid @RequestBody Entrada entrada) {
		return "ok";
	}

	@GetMapping("/sonda/explota")
	String explota() {
		throw new IllegalStateException("detalle interno secreto jdbc:postgresql://host");
	}

	@GetMapping("/sonda/negocio")
	String negocio() {
		throw new ExcepcionNegocio(HttpStatus.CONFLICT, "CODIGO_X", "Conflicto de prueba.");
	}
}
