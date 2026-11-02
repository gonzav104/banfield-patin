package com.banfieldpatin.backend.familias.tutores.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.banfieldpatin.backend.familias.tutores.Tutor;

class TutorResumenTest {

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();

	@Test
	void elTutorDentroDeLaFamiliaEnmascaraSuDniSinCambiarElRestoDeLosDatos() {
		Tutor t = Tutor.crear(escuelaId, familiaId, "Ana", "Gomez", "27123456", "11 5555", "ana@example.com", "Madre");

		TutorResumen r = TutorResumen.de(t);

		assertThat(r.dniEnmascarado()).isEqualTo("•••••456");
		assertThat(r.nombre()).isEqualTo("Ana");
		assertThat(r.apellido()).isEqualTo("Gomez");
		assertThat(r.telefono()).isEqualTo("11 5555");
		assertThat(r.email()).isEqualTo("ana@example.com");
		assertThat(r.parentesco()).isEqualTo("Madre");
		assertThat(r.activo()).isTrue();
		assertThat(t.getDni()).as("la entidad conserva el DNI completo").isEqualTo("27123456");
	}

	@Test
	void unTutorSinDniQuedaSinDniEnmascarado() {
		Tutor t = Tutor.crear(escuelaId, familiaId, "Ana", "Gomez", null, null, null, null);

		assertThat(TutorResumen.de(t).dniEnmascarado()).isNull();
	}

	@Test
	void conservaElLargoParaDnisDeDistintaLongitud() {
		for (String dni : new String[] { "4412345", "27123456", "271234567" }) {
			Tutor t = Tutor.crear(escuelaId, familiaId, "Ana", "Gomez", dni, null, null, null);

			String enmascarado = TutorResumen.de(t).dniEnmascarado();

			assertThat(enmascarado).hasSize(dni.length()).endsWith(dni.substring(dni.length() - 3));
			assertThat(enmascarado.replace("•", "")).hasSize(3);
		}
	}

	@Test
	void elDetalleIndividualDelTutorSigueTeniendoElDniCompleto() {
		Tutor t = Tutor.crear(escuelaId, familiaId, "Ana", "Gomez", "27123456", null, null, null);

		assertThat(TutorRespuesta.de(t).dni()).isEqualTo("27123456");
		assertThat(TutorResumen.class.getRecordComponents()).extracting(c -> c.getName()).doesNotContain("dni");
		assertThat(TutorRespuesta.class.getRecordComponents()).extracting(c -> c.getName()).doesNotContain("dniEnmascarado");
	}

	@Test
	void elToStringNoImprimeDatosPersonales() {
		Tutor t = Tutor.crear(escuelaId, familiaId, "Ana", "Gomez", "27123456", null, null, null);
		TutorResumen r = TutorResumen.de(t);

		assertThat(r.toString()).isEqualTo("TutorResumen[id=" + r.id() + "]").doesNotContain("Ana", "456");
	}
}
