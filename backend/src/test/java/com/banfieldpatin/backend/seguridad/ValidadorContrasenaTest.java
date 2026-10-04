package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidadorContrasenaTest {

	private final ValidadorContrasena validador = new ValidadorContrasena();

	@Test
	void aceptaContrasenaDeLongitudValida() {
		assertThat(validador.esValida("a".repeat(10))).isTrue();
		assertThat(validador.esValida("a".repeat(72))).isTrue();
	}

	@Test
	void rechazaContrasenaDe73Bytes() {
		assertThat(validador.esValida("a".repeat(73))).isFalse();
	}

	@Test
	void rechazaContrasenaMasCortaQueElMinimo() {
		assertThat(validador.esValida("a".repeat(9))).isFalse();
	}

	@Test
	void rechazaMultibyteConMenosDe72CaracteresPeroMasDe72Bytes() {
		String multibyte = "ñ".repeat(40); // 40 caracteres, 80 bytes UTF-8
		assertThat(multibyte.length()).isLessThanOrEqualTo(72);
		assertThat(validador.esValida(multibyte)).isFalse();
	}

	@Test
	void rechazaNulaYEnBlanco() {
		assertThat(validador.esValida(null)).isFalse();
		assertThat(validador.esValida(" ".repeat(20))).isFalse();
		assertThat(validador.isValid(null, null)).isFalse();
	}

	@Test
	void respetaMinimoConfigurable() {
		assertThat(new ValidadorContrasena(14).esValida("a".repeat(13))).isFalse();
		assertThat(new ValidadorContrasena(14).esValida("a".repeat(14))).isTrue();
	}
}
