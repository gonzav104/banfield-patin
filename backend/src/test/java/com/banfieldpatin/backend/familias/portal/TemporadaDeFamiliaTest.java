package com.banfieldpatin.backend.familias.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.banfieldpatin.backend.familias.portal.dto.TemporadaDeFamilia;
import com.banfieldpatin.backend.temporadas.EstadoDeportivo;

class TemporadaDeFamiliaTest {

	@Test
	void elToStringNuncaImprimeLosDatosDeportivosDeUnMenor() {
		UUID temporadaId = UUID.randomUUID();
		TemporadaDeFamilia t = new TemporadaDeFamilia(temporadaId, (short) 2027, true, "Sub 12", "Femenino", "A", "007",
				EstadoDeportivo.SUSPENDIDO);

		assertThat(t.toString()).isEqualTo("TemporadaDeFamilia[temporadaId=" + temporadaId + "]")
				.doesNotContain("Sub 12", "Femenino", "007", "SUSPENDIDO");
	}

	@Test
	void exponeExactamenteLosOchoCamposDelContrato() {
		assertThat(TemporadaDeFamilia.class.getRecordComponents()).extracting(c -> c.getName()).containsExactly("temporadaId",
				"anio", "temporadaActiva", "categoria", "rama", "divisional", "numeroCorredor", "estadoDeportivo");
	}
}
