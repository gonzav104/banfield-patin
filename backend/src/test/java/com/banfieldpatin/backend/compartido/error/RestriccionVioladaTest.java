package com.banfieldpatin.backend.compartido.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class RestriccionVioladaTest {

	private Logger logger;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void capturar() {
		logger = (Logger) LoggerFactory.getLogger(RestriccionViolada.class);
		logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
	}

	@AfterEach
	void liberar() {
		logger.detachAppender(logs);
	}

	private static DataIntegrityViolationException conValores(String restriccion) {
		String pg = "ERROR: duplicate key value violates unique constraint \"x\"\n  Detail: Key (dni)=(12345678) already exists.";
		return new DataIntegrityViolationException("could not execute statement [" + pg + "]",
				new ConstraintViolationException(pg, new SQLException(pg), "insert into t values ('12345678')",
						ConstraintKind.UNIQUE, restriccion));
	}

	@Test
	void unaRestriccionMapeadaSeRegistraEnWarnConSoloRestriccionOperacionYClase() {
		RestriccionViolada.registrarMapeada("VinculoAdminService.vincular", conValores("uq_fd_principal_activo"));

		assertThat(logs.list).singleElement().satisfies(e -> {
			assertThat(e.getLevel()).isEqualTo(Level.WARN);
			assertThat(e.getFormattedMessage()).isEqualTo("Violacion de restriccion mapeada a conflicto: "
					+ "restriccion=uq_fd_principal_activo operacion=VinculoAdminService.vincular "
					+ "excepcion=org.springframework.dao.DataIntegrityViolationException");
			assertThat(e.getThrowableProxy()).isNull();
			assertThat(e.getArgumentArray()).containsExactly("uq_fd_principal_activo", "VinculoAdminService.vincular",
					"org.springframework.dao.DataIntegrityViolationException");
		});
	}

	@Test
	void unaRestriccionSinMapearSeRegistraEnErrorSinTrazaNiMensajeNiValores() {
		RestriccionViolada.registrarNoMapeada("POST /api/admin/x/{id}", conValores("ck_cualquiera"));

		assertThat(logs.list).singleElement().satisfies(e -> {
			assertThat(e.getLevel()).isEqualTo(Level.ERROR);
			assertThat(e.getFormattedMessage()).contains("restriccion=ck_cualquiera")
					.contains("operacion=POST /api/admin/x/{id}").doesNotContain("12345678").doesNotContain("duplicate")
					.doesNotContain("Detail:").doesNotContain("insert into");
			assertThat(e.getThrowableProxy()).isNull();
		});
	}

	@Test
	void unNombreQueNoEsUnIdentificadorOLaFaltaDeNombreSeImprimenComoDesconocida() {
		RestriccionViolada.registrarMapeada("op", conValores("uq con espacios; 12345678 \n"));
		RestriccionViolada.registrarNoMapeada("op", new DataIntegrityViolationException("sin causa 12345678"));
		RestriccionViolada.registrarNoMapeada("op", conValores("a".repeat(64)));

		assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).allSatisfy(m -> {
			assertThat(m).contains("restriccion=desconocida").doesNotContain("12345678").doesNotContain("con espacios");
		});
		assertThat(logs.list).hasSize(3);
	}

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
