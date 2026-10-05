package com.banfieldpatin.backend.compartido.error;

import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/** Extrae el nombre de la restriccion de BD violada para mapear conflictos a 409 sin exponer el nombre al cliente. */
public final class RestriccionViolada {

	private RestriccionViolada() {
	}

	/** Nombre de la restriccion segun Hibernate, o vacio si la causa no es una violacion de restriccion con nombre. */
	public static Optional<String> nombre(DataIntegrityViolationException e) {
		for (Throwable causa = e; causa != null; causa = causa.getCause() == causa ? null : causa.getCause()) {
			if (causa instanceof ConstraintViolationException violacion && violacion.getConstraintName() != null) {
				return Optional.of(violacion.getConstraintName());
			}
		}
		return Optional.empty();
	}
}
