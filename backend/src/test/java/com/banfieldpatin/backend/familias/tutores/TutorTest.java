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
		Tutor t = tutor();

		t.actualizar("Otra", "Persona", null, null, null, null);

		assertThat(t.getEscuelaId()).isEqualTo(escuelaId);
		assertThat(t.getFamiliaId()).isEqualTo(familiaId);
		assertThat(t.isActivo()).isTrue();
	}

	@Test
	void laFamiliaLaEscuelaYElEstadoSonInmutablesEnElMapeo() throws Exception {
		for (String campo : new String[] { "familiaId", "escuelaId", "activo" }) {
			Field f = Tutor.class.getDeclaredField(campo);
			assertThat(f.getAnnotation(Column.class).updatable()).as(campo).isFalse();
		}
	}

	@Test
	void noExisteMapeoDeUsuarioNiSettersNiAsociaciones() {
		assertThat(Tutor.class.getDeclaredFields()).extracting(Field::getName)
				.doesNotContain("usuarioId", "usuario", "familia");
		assertThat(Tutor.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
				.noneMatch(n -> n.startsWith("set"));
	}
}
