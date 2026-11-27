package com.banfieldpatin.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UuidsDeTestTest {

	@Test
	void esDeterministaPorSemillaYDistintoEntreSemillas() {
		assertThat(UuidsDeTest.sin("a", "007")).isEqualTo(UuidsDeTest.sin("a", "007"));
		assertThat(UuidsDeTest.sin("a", "007")).isNotEqualTo(UuidsDeTest.sin("b", "007"));
	}

	@Test
	void nuncaContieneUnTextoProhibidoSinImportarMayusculas() {
		List<String> prohibidos = List.of("007", "456", "222", "2016", "abc", "DEF", "0");
		for (int i = 0; i < 500; i++) {
			String texto = UuidsDeTest.sin("semilla-" + i, prohibidos.toArray(new String[0])).toString();
			for (String prohibido : prohibidos) {
				assertThat(texto.toLowerCase()).doesNotContain(prohibido.toLowerCase());
			}
		}
	}

	@Test
	void esUnUuidValidoYSiNoHayNadaProhibidoNoRestringeNada() {
		UUID u = UuidsDeTest.sin("x");
		assertThat(UUID.fromString(u.toString())).isEqualTo(u);
	}

	@Test
	void siNoSePuedeSatisfacerFallaEnVezDeDevolverUnoInvalido() {
		// «-» aparece en todo UUID: ninguno puede evitarlo
		assertThatThrownBy(() -> UuidsDeTest.sin("x", "-")).isInstanceOf(IllegalStateException.class);
	}
}
