package com.banfieldpatin.backend.compartido.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;

class FiltroEstadoTest {

	@Test
	void ausenteOEnBlancoDevuelveElValorPorDefecto() {
		assertThat(FiltroEstado.de(null, FiltroEstado.TODOS)).isEqualTo(FiltroEstado.TODOS);
		assertThat(FiltroEstado.de("  ", FiltroEstado.ACTIVOS)).isEqualTo(FiltroEstado.ACTIVOS);
	}

	@Test
	void reconoceLosTresValores() {
		assertThat(FiltroEstado.de("TODOS", FiltroEstado.ACTIVOS)).isEqualTo(FiltroEstado.TODOS);
		assertThat(FiltroEstado.de("ACTIVOS", FiltroEstado.TODOS)).isEqualTo(FiltroEstado.ACTIVOS);
		assertThat(FiltroEstado.de(" INACTIVOS ", FiltroEstado.TODOS)).isEqualTo(FiltroEstado.INACTIVOS);
	}

	@Test
	void unValorInvalidoDa400SolicitudInvalida() {
		for (String invalido : new String[] { "x", "activos", "ACTIVO", "1" }) {
			assertThatThrownBy(() -> FiltroEstado.de(invalido, FiltroEstado.TODOS))
					.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
						assertThat(e.getEstado()).isEqualTo(HttpStatus.BAD_REQUEST);
						assertThat(e.getCodigo()).isEqualTo("SOLICITUD_INVALIDA");
					});
		}
	}
}
