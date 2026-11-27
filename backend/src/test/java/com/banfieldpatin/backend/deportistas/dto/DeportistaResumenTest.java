package com.banfieldpatin.backend.deportistas.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.banfieldpatin.backend.UuidsDeTest;

import com.banfieldpatin.backend.FixturesDominio;
import com.banfieldpatin.backend.deportistas.Deportista;

class DeportistaResumenTest {

	// Determinista: el texto imprime este id y la prueba afirma que no contiene «222» (parte del DNI enmascarado).
	private final UUID id = UuidsDeTest.sin("id", "222", "Juan", "Perez");

	@Test
	void elResumenEnmascaraElDniYNoTieneUnComponenteDniCompleto() {
		Deportista d = FixturesDominio.deportista(id, UUID.randomUUID(), "30111222", "Juan", "Perez", true);

		DeportistaResumen r = DeportistaResumen.de(d);

		assertThat(r.dniEnmascarado()).isEqualTo("•••••222");
		assertThat(r.id()).isEqualTo(id);
		assertThat(r.activo()).isTrue();
		assertThat(DeportistaResumen.class.getRecordComponents()).extracting(c -> c.getName())
				.containsExactly("id", "nombre", "apellido", "dniEnmascarado", "fechaNacimiento", "activo");
	}

	@Test
	void elDniCompletoDeLaEntidadNoCambia() {
		Deportista d = FixturesDominio.deportista(id, UUID.randomUUID(), "30111222", "Juan", "Perez", true);

		DeportistaResumen.de(d);

		assertThat(d.getDni()).isEqualTo("30111222");
	}

	@Test
	void elToStringNoImprimeDatosPersonales() {
		DeportistaResumen r = new DeportistaResumen(id, "Juan", "Perez", "•••••222", LocalDate.of(2012, 5, 10), true);

		assertThat(r.toString()).isEqualTo("DeportistaResumen[id=" + id + "]").doesNotContain("Juan", "222");
	}
}
