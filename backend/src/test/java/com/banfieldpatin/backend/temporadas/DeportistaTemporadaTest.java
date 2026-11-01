package com.banfieldpatin.backend.temporadas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DeportistaTemporadaTest {

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID deportistaId = UUID.randomUUID();
	private final UUID temporadaId = UUID.randomUUID();

	@Test
	void unaFilaNuevaGuardaSuIdentidadYSusDatosTalCualLlegan() {
		DeportistaTemporada dt = DeportistaTemporada.crear(escuelaId, deportistaId, temporadaId, "Sub 12", "Femenino",
				"A", "007", EstadoDeportivo.SUSPENDIDO);

		assertThat(dt.getEscuelaId()).isEqualTo(escuelaId);
		assertThat(dt.getDeportistaId()).isEqualTo(deportistaId);
		assertThat(dt.getTemporadaId()).isEqualTo(temporadaId);
		assertThat(dt.getCategoria()).isEqualTo("Sub 12");
		assertThat(dt.getRama()).isEqualTo("Femenino");
		assertThat(dt.getDivisional()).isEqualTo("A");
		assertThat(dt.getNumeroCorredor()).as("conserva los ceros iniciales").isEqualTo("007");
		assertThat(dt.getEstadoDeportivo()).isEqualTo(EstadoDeportivo.SUSPENDIDO);
	}

	private DeportistaTemporada fila() {
		return DeportistaTemporada.crear(escuelaId, deportistaId, temporadaId, "Sub 12", "Femenino", "A", "007",
				EstadoDeportivo.ACTIVO);
	}

	@Test
	void reemplazarInformaSoloLosNombresDeLosCamposQueCambiaronEnElOrdenDeLaSolicitud() {
		DeportistaTemporada dt = fila();

		List<String> cambios = dt.reemplazar("Sub 14", "Femenino", "B", "007", EstadoDeportivo.SUSPENDIDO);

		assertThat(cambios).containsExactly("categoria", "divisional", "estadoDeportivo");
		assertThat(dt.getCategoria()).isEqualTo("Sub 14");
		assertThat(dt.getRama()).isEqualTo("Femenino");
		assertThat(dt.getDivisional()).isEqualTo("B");
		assertThat(dt.getNumeroCorredor()).isEqualTo("007");
		assertThat(dt.getEstadoDeportivo()).isEqualTo(EstadoDeportivo.SUSPENDIDO);
	}

	@Test
	void reemplazarConLosMismosValoresNoCambiaNadaYQuitarUnDatoLoPasaANull() {
		DeportistaTemporada dt = fila();

		assertThat(dt.reemplazar("Sub 12", "Femenino", "A", "007", EstadoDeportivo.ACTIVO)).isEmpty();
		assertThat(dt.reemplazar(null, "Femenino", "A", null, EstadoDeportivo.ACTIVO)).containsExactly("categoria",
				"numeroCorredor");
		assertThat(dt.getCategoria()).isNull();
		assertThat(dt.getNumeroCorredor()).isNull();
	}

	@Test
	void reemplazarNuncaCambiaLaIdentidadDeLaFila() {
		DeportistaTemporada dt = fila();

		dt.reemplazar(null, null, null, null, EstadoDeportivo.INACTIVO);

		assertThat(dt.getEscuelaId()).isEqualTo(escuelaId);
		assertThat(dt.getDeportistaId()).isEqualTo(deportistaId);
		assertThat(dt.getTemporadaId()).isEqualTo(temporadaId);
	}

	@Test
	void losDatosOpcionalesPuedenSerNulos() {
		DeportistaTemporada dt = DeportistaTemporada.crear(escuelaId, deportistaId, temporadaId, null, null, null, null,
				EstadoDeportivo.ACTIVO);

		assertThat(dt.getCategoria()).isNull();
		assertThat(dt.getNumeroCorredor()).isNull();
	}
}
