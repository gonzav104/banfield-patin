package com.banfieldpatin.backend.familias;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class FamiliaTest {

	private final UUID escuelaId = UUID.randomUUID();

	@Test
	void crearDejaLaFamiliaActivaEnLaEscuelaIndicada() {
		Familia f = Familia.crear(escuelaId, "Perez");

		assertThat(f.isActiva()).isTrue();
		assertThat(f.getEscuelaId()).isEqualTo(escuelaId);
		assertThat(f.getNombreReferencia()).isEqualTo("Perez");
	}

	@Test
	void renombrarDevuelveTrueSoloSiElNombreCambia() {
		Familia f = Familia.crear(escuelaId, "Perez");

		assertThat(f.renombrar("Perez")).isFalse();
		assertThat(f.renombrar("Gomez")).isTrue();
		assertThat(f.getNombreReferencia()).isEqualTo("Gomez");
		assertThat(f.renombrar("Gomez")).isFalse();
	}

	@Test
	void desactivarYActivarDevuelvenTrueSoloEnUnCambioRealDeEstado() {
		Familia f = Familia.crear(escuelaId, "Perez");

		assertThat(f.activar()).isFalse();
		assertThat(f.desactivar()).isTrue();
		assertThat(f.isActiva()).isFalse();
		assertThat(f.desactivar()).isFalse();
		assertThat(f.activar()).isTrue();
		assertThat(f.isActiva()).isTrue();
	}
}
