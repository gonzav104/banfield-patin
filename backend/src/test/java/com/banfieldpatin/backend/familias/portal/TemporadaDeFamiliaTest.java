package com.banfieldpatin.backend.familias.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.banfieldpatin.backend.UuidsDeTest;

import com.banfieldpatin.backend.familias.portal.dto.TemporadaDeFamilia;
import com.banfieldpatin.backend.temporadas.EstadoDeportivo;

class TemporadaDeFamiliaTest {

	@Test
	void elToStringNuncaImprimeLosDatosDeportivosDeUnMenor() {
		// Determinista: un UUID aleatorio podria contener «007» y falsear la afirmacion de que el texto no imprime el dato.
		UUID temporadaId = UuidsDeTest.sin("temporadaId", "007", "Sub 12", "Femenino");
		TemporadaDeFamilia t = new TemporadaDeFamilia(temporadaId, (short) 2027, true, "Sub 12", "Femenino", "A", "007",
				EstadoDeportivo.SUSPENDIDO);
		// Control: los datos que el texto no debe imprimir SON los del objeto (la ausencia dice algo).
		assertThat(t.categoria()).isEqualTo("Sub 12");
		assertThat(t.rama()).isEqualTo("Femenino");
		assertThat(t.numeroCorredor()).isEqualTo("007");

		assertThat(t.toString()).isEqualTo("TemporadaDeFamilia[temporadaId=" + temporadaId + "]")
				.doesNotContain("Sub 12", "Femenino", "007", "SUSPENDIDO");
	}

	@Test
	void exponeExactamenteLosOchoCamposDelContrato() {
		assertThat(TemporadaDeFamilia.class.getRecordComponents()).extracting(c -> c.getName()).containsExactly("temporadaId",
				"anio", "temporadaActiva", "categoria", "rama", "divisional", "numeroCorredor", "estadoDeportivo");
	}
}
