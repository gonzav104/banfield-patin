package com.banfieldpatin.backend.compartido.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BusquedaTest {

	@Test
	void recortaLosEspaciosDeLosExtremos() {
		assertThat(Busqueda.patronLike("   perez \t")).isEqualTo("perez");
	}

	@Test
	void nuloOEnBlancoEsSinFiltro() {
		assertThat(Busqueda.patronLike(null)).isEmpty();
		assertThat(Busqueda.patronLike("    ")).isEmpty();
	}

	@Test
	void escapaLosComodinesYElCaracterDeEscapeConAdmiracion() {
		assertThat(Busqueda.patronLike("50%_!")).isEqualTo("50!%!_!!");
	}

	@Test
	void acotaA100CaracteresAntesDeEscapar() {
		assertThat(Busqueda.patronLike("a".repeat(150))).hasSize(100);
		// El corte es sobre el texto original: 100 '%' escapados dan 200 caracteres, no 100.
		assertThat(Busqueda.patronLike("%".repeat(150))).hasSize(200);
	}

	@Test
	void digitosQuitaPuntosYEspaciosSoloSiTodoSonDigitos() {
		assertThat(Busqueda.digitos("12.345")).isEqualTo("12345");
		assertThat(Busqueda.digitos(" 12 345 678 ")).isEqualTo("12345678");
		assertThat(Busqueda.digitos("12a45")).isEmpty();
		assertThat(Busqueda.digitos("12-345")).isEmpty();
		assertThat(Busqueda.digitos("")).isEmpty();
		assertThat(Busqueda.digitos(null)).isEmpty();
		assertThat(Busqueda.digitos("...")).isEmpty();
	}
}
