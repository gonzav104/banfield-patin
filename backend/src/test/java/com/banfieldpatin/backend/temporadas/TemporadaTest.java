package com.banfieldpatin.backend.temporadas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TemporadaTest {

	private final UUID escuelaId = UUID.randomUUID();

	@Test
	void unaTemporadaNuevaNaceInactivaConSuEscuelaYSuAnio() {
		Temporada t = Temporada.crear(escuelaId, 2027);

		assertThat(t.isActiva()).isFalse();
		assertThat(t.getEscuelaId()).isEqualTo(escuelaId);
		assertThat(t.getAnio()).isEqualTo((short) 2027);
	}

	@Test
	void activarInformaElCambioRealYSegundaVezNoHaceNada() {
		Temporada t = Temporada.crear(escuelaId, 2027);

		assertThat(t.activar()).isTrue();
		assertThat(t.isActiva()).isTrue();
		assertThat(t.activar()).isFalse();
		assertThat(t.isActiva()).isTrue();
	}

	@Test
	void desactivarInformaElCambioRealYSegundaVezNoHaceNada() {
		Temporada t = Temporada.crear(escuelaId, 2027);
		t.activar();

		assertThat(t.desactivar()).isTrue();
		assertThat(t.isActiva()).isFalse();
		assertThat(t.desactivar()).isFalse();
	}
}
