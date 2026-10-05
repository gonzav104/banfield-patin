package com.banfieldpatin.backend.compartido.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class RestriccionVioladaTest {

	@Test
	void extraeElNombreDeLaRestriccionDeLaCausaDeHibernate() {
		var causa = new ConstraintViolationException("duplicado", new SQLException("x"), "insert ...",
				ConstraintKind.UNIQUE, "uq_deportista_dni_escuela");

		assertThat(RestriccionViolada.nombre(new DataIntegrityViolationException("conflicto", causa)))
				.contains("uq_deportista_dni_escuela");
	}

	@Test
	void encuentraLaCausaAunqueEsteAnidada() {
		var violacion = new ConstraintViolationException("duplicado", new SQLException("x"), "insert ...",
				ConstraintKind.UNIQUE, "uq_fd_principal_activo");

		assertThat(RestriccionViolada.nombre(
				new DataIntegrityViolationException("conflicto", new RuntimeException("envoltorio", violacion))))
				.contains("uq_fd_principal_activo");
	}

	@Test
	void sinCausaDeHibernateOSinNombreDevuelveVacio() {
		assertThat(RestriccionViolada.nombre(new DataIntegrityViolationException("sin causa"))).isEmpty();
		assertThat(RestriccionViolada.nombre(
				new DataIntegrityViolationException("otra causa", new SQLException("x")))).isEmpty();
		assertThat(RestriccionViolada.nombre(new DataIntegrityViolationException("sin nombre",
				new ConstraintViolationException("m", new SQLException("x"), "insert ...",
						ConstraintKind.OTHER, null)))).isEmpty();
	}
}
