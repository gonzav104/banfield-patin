package com.banfieldpatin.backend.familias.tutores;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;

class TutorTest {

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();

	private Tutor tutor() {
		return Tutor.crear(escuelaId, familiaId, "Ana", "Perez", "30111222", "11-5555-0000", "ana@example.com", "Madre");
	}

	@Test
	void crearDejaElTutorActivoEnLaEscuelaYFamiliaIndicadas() {
		Tutor t = tutor();

		assertThat(t.getEscuelaId()).isEqualTo(escuelaId);
		assertThat(t.getFamiliaId()).isEqualTo(familiaId);
		assertThat(t.isActivo()).isTrue();
		assertThat(t.getNombre()).isEqualTo("Ana");
		assertThat(t.getDni()).isEqualTo("30111222");
	}

	@Test
	void actualizarConLosMismosDatosNoDevuelveCambios() {
		assertThat(tutor().actualizar("Ana", "Perez", "30111222", "11-5555-0000", "ana@example.com", "Madre")).isEmpty();
	}

	@Test
	void actualizarDevuelveSoloLosNombresDeLosCamposCambiados() {
		Tutor t = tutor();

		var cambios = t.actualizar("Ana", "Perez", "30111222", "11-6666-1111", "ana@example.com", "Madre");

		assertThat(cambios).containsExactly("telefono");
		assertThat(t.getTelefono()).isEqualTo("11-6666-1111");
		assertThat(cambios.toString()).doesNotContain("11-6666-1111");
	}

	@Test
	void actualizarPuedeVaciarLosOpcionalesYReportaVariosCamposEnOrden() {
		Tutor t = tutor();

		var cambios = t.actualizar("Lucia", "Perez", null, null, null, null);

		assertThat(cambios).containsExactly("nombre", "dni", "telefono", "email", "parentesco");
		assertThat(t.getDni()).isNull();
		assertThat(t.getEmail()).isNull();
	}

	@Test
	void actualizarNoTocaLaEscuelaLaFamiliaNiElEstado() {
		for (boolean activo : new boolean[] { true, false }) {
			Tutor t = tutor();
			if (!activo) {
				t.desactivar();
			}
			t.actualizar("Otra", "Persona", null, null, null, null);
			assertThat(t.getEscuelaId()).isEqualTo(escuelaId);
			assertThat(t.getFamiliaId()).isEqualTo(familiaId);
			assertThat(t.isActivo()).isEqualTo(activo);
		}
	}

	@Test
	void familiaYEscuelaSonInmutablesYElMapeoAdmiteElCambioDeEstado() throws Exception {
		for (String campo : new String[] { "familiaId", "escuelaId" }) {
			Field f = Tutor.class.getDeclaredField(campo);
			assertThat(f.getAnnotation(Column.class).updatable()).as(campo).isFalse();
		}
		assertThat(Tutor.class.getDeclaredField("activo").getAnnotation(Column.class).updatable()).isTrue();
	}

	@Test
	void noExistenSettersNiAsociacionesYLaCuentaSoloSeMapeaComoIdSinEscrituraPosterior() throws Exception {
		// U21-BIS C1 mapea usuario_id (vínculo declarativo), pero solo como id: sin asociación JPA y sin UPDATE de Hibernate.
		assertThat(Tutor.class.getDeclaredFields()).extracting(Field::getName).doesNotContain("usuario", "familia");
		for (String campo : java.util.List.of("usuarioId", "origen", "verificacion", "vinculoCuenta", "declaradoPor")) {
			assertThat(Tutor.class.getDeclaredField(campo).getAnnotation(Column.class).updatable()).as(campo).isFalse();
		}
		assertThat(Tutor.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
				.noneMatch(n -> n.startsWith("set"));
	}
}
