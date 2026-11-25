package com.banfieldpatin.backend.familias.responsables;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

class CierreVigenciasTutorTest {
	private static final String FK_FAMILIA = "fk_td_familia_activa";

	@Test
	void soloLaViolacionDeLaFkEsperadaEsUnConflictoDeCierre() {
		var causa = violacion("23503", FK_FAMILIA);
		assertThatThrownBy(() -> CierreVigenciasTutor.confirmarBaja(FK_FAMILIA, () -> {
			throw causa;
		})).isInstanceOf(CannotAcquireLockException.class).hasCause(causa);
	}

	@Test
	void otraRestriccionConElMismoSqlstateConservaElErrorOriginal() {
		sinTraducir(violacion("23503", "fk_td_deportista_activo"));
	}

	@Test
	void otroSqlstateConElMismoNombreConservaElErrorOriginal() {
		sinTraducir(violacion("23514", FK_FAMILIA));
	}

	private static void sinTraducir(DataIntegrityViolationException causa) {
		assertThatThrownBy(() -> CierreVigenciasTutor.confirmarBaja(FK_FAMILIA, () -> {
			throw causa;
		})).isSameAs(causa);
	}

	private static DataIntegrityViolationException violacion(String estado, String restriccion) {
		return new DataIntegrityViolationException("violación de prueba",
				new ConstraintViolationException("violación de prueba", new SQLException("SQL", estado),
						"update ...", ConstraintViolationException.ConstraintKind.OTHER, restriccion));
	}
}
