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

	/** Texto al estilo de la traduccion de un 55P03 de PostgreSQL: nombra la tabla, la sentencia y un id. */
	static final String MENSAJE_BLOQUEO = "could not obtain lock on row in relation \"deportista\"; SQL [select d1_0.id from "
			+ "gestion_patin.deportista d1_0 where d1_0.id in (?) for update of d1_0]; ID 7b3f2c1e-1111-2222-3333-444455556666";

	@PostMapping("/sonda/bloqueo/{id}")
	String bloqueo(@org.springframework.web.bind.annotation.PathVariable String id) {
		throw new org.springframework.dao.CannotAcquireLockException(MENSAJE_BLOQUEO,
				new java.sql.SQLException("ERROR: canceling statement due to lock timeout", "55P03"));
	}

	@GetMapping("/sonda/deadlock")
	String deadlock() {
		throw new org.springframework.dao.DeadlockLoserDataAccessException(MENSAJE_BLOQUEO,
				new java.sql.SQLException("ERROR: deadlock detected", "40P01"));
	}

	/** Valores que JAMAS deben aparecer en un log: un DNI y la linea "Detail: Key (...)" de PostgreSQL. */
	static final String DNI_SECRETO = "37123456";
	static final String MENSAJE_PG = "ERROR: duplicate key value violates unique constraint \"uq_otra\"\n  Detail: Key (escuela_id, dni)=("
			+ "11111111-2222-3333-4444-555555555555, " + DNI_SECRETO + ") already exists.";

	/** Violacion de restriccion SIN mapear: la trata el @ExceptionHandler de DataIntegrityViolationException (500 saneado). */
	@PostMapping("/sonda/violacion")
	String violacion() {
		throw new org.springframework.dao.DataIntegrityViolationException("could not execute statement [" + MENSAJE_PG + "]",
				new org.hibernate.exception.ConstraintViolationException(MENSAJE_PG, new java.sql.SQLException(MENSAJE_PG),
						"insert into deportista ...", org.hibernate.exception.ConstraintViolationException.ConstraintKind.UNIQUE,
						"uq_otra"));
	}

	@GetMapping("/sonda/negocio")
	String negocio() {
		throw new ExcepcionNegocio(HttpStatus.CONFLICT, "CODIGO_X", "Conflicto de prueba.");
	}
}
