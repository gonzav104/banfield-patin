package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Prueba (sin base de datos ni Docker) la salvaguarda que impide apuntar las pruebas db a una base compartida. */
class DestinoDbPruebaTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"jdbc:postgresql://localhost:5432/banfield_test",
			"jdbc:postgresql://127.0.0.1/banfield_TEST",
			"jdbc:postgresql://[::1]:5433/test_descartable?sslmode=disable" })
	void aceptaBasesLocalesDescartables(String url) {
		assertThatCode(() -> DestinoDbPrueba.validarUrl(url)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"jdbc:postgresql://db.abcdefgh.supabase.co:5432/postgres_test",
			"jdbc:postgresql://aws-0-sa-east-1.pooler.supabase.com:6543/test",
			"jdbc:postgresql://10.0.0.5:5432/banfield_test",
			"jdbc:postgresql://localhost.evil.example/banfield_test",
			"jdbc:postgresql://localhost:5432/postgres",
			"jdbc:postgresql://localhost:5432/",
			"jdbc:postgresql:///banfield_test",
			"jdbc:mysql://localhost:3306/banfield_test",
			"postgresql://localhost/banfield_test",
			"" })
	void rechazaCualquierOtroDestino(String url) {
		assertThatThrownBy(() -> DestinoDbPrueba.validarUrl(url)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void lasCredencialesNuncaApareceEnElMensajeDeRechazo() {
		String url = "jdbc:postgresql://db.example.supabase.co/test?user=postgres&password=SECRETO-QUE-NO-DEBE-SALIR";

		assertThatThrownBy(() -> DestinoDbPrueba.validarUrl(url))
				.isInstanceOf(IllegalStateException.class)
				.satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRETO-QUE-NO-DEBE-SALIR"));
	}
}
