package com.banfieldpatin.backend.deportistas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EnmascaradoDniTest {

	@Test
	void muestraSoloLosUltimosTresDigitosYOcultaElRestoConservandoElLargoParaOchoDigitos() {
		assertThat(EnmascaradoDni.de("30111322")).isEqualTo("•••••322");
	}

	@Test
	void conservaElLargoOriginalParaSieteYNueveDigitos() {
		assertThat(EnmascaradoDni.de("4412345")).isEqualTo("••••345");
		assertThat(EnmascaradoDni.de("301113221")).isEqualTo("••••••221");
		assertThat(EnmascaradoDni.de("4412345")).hasSize(7);
		assertThat(EnmascaradoDni.de("301113221")).hasSize(9);
	}

	@Test
	void unDniOpcionalAusenteSigueAusente() {
		assertThat(EnmascaradoDni.de(null)).isNull();
	}

	@Test
	void unValorCortoNuncaDevuelveMasDeLoQueDebe() {
		assertThat(EnmascaradoDni.de("")).isEmpty();
		assertThat(EnmascaradoDni.de("1")).isEqualTo("•");
		assertThat(EnmascaradoDni.de("12")).isEqualTo("••");
		assertThat(EnmascaradoDni.de("123")).isEqualTo("•••");
		assertThat(EnmascaradoDni.de("1234")).isEqualTo("•234");
	}

	@Test
	void ningunDigitoOcultoReapareceNiSeAgregaNadaMas() {
		String dni = "30111322";
		String enmascarado = EnmascaradoDni.de(dni);

		assertThat(enmascarado.chars().filter(c -> c == '•').count()).isEqualTo(dni.length() - 3);
		assertThat(enmascarado).endsWith(dni.substring(dni.length() - 3)).doesNotContain("30111");
		assertThat(enmascarado.replace("•", "")).hasSize(3).matches("[0-9]{3}");
	}

	@Test
	void esDeterministaYNoModificaLaEntrada() {
		String dni = "27123456";

		assertThat(EnmascaradoDni.de(dni)).isEqualTo(EnmascaradoDni.de(dni));
		assertThat(dni).isEqualTo("27123456");
	}
}
