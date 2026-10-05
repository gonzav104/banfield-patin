package com.banfieldpatin.backend.compartido.error;

import java.util.Optional;
import java.util.regex.Pattern;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Extrae el nombre de la restriccion de BD violada para mapear conflictos a 409 sin exponer el nombre al cliente, y
 * deja UNA linea de log saneada por cada violacion (mapeada: WARN; sin mapear: ERROR). El log lleva solo el nombre de la
 * restriccion, la operacion y la clase de la excepcion: nunca valores, ids de personas, el mensaje o el detalle de
 * PostgreSQL, el mensaje de Hibernate ni la traza (el mensaje del servidor incluye los valores de la clave, por ejemplo
 * un DNI). El logger de Hibernate {@code org.hibernate.orm.jdbc.error} esta apagado por la misma razon (application.yml).
 */
public final class RestriccionViolada {

	private static final Logger log = LoggerFactory.getLogger(RestriccionViolada.class);
	private static final String DESCONOCIDA = "desconocida";
	// Un identificador de PostgreSQL (hasta 63 caracteres); cualquier otra cosa no se imprime.
	private static final Pattern IDENTIFICADOR = Pattern.compile("[A-Za-z0-9_]{1,63}");

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

	/** Una carrera resuelta por la base y traducida a 409: WARN con restriccion, operacion y clase de la excepcion. */
	public static void registrarMapeada(String operacion, DataIntegrityViolationException e) {
		log.warn("Violacion de restriccion mapeada a conflicto: restriccion={} operacion={} excepcion={}",
				restriccionParaLog(e), operacion, e.getClass().getName());
	}

	/** Una violacion que ningun servicio mapeo y acaba en 500: ERROR con restriccion, operacion y clase, sin traza. */
	public static void registrarNoMapeada(String operacion, DataIntegrityViolationException e) {
		log.error("Violacion de restriccion sin mapear (500): restriccion={} operacion={} excepcion={}",
				restriccionParaLog(e), operacion, e.getClass().getName());
	}

	private static String restriccionParaLog(DataIntegrityViolationException e) {
		return nombre(e).filter(n -> IDENTIFICADOR.matcher(n).matches()).orElse(DESCONOCIDA);
	}
}
