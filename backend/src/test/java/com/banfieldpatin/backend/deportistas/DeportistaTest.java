package com.banfieldpatin.backend.deportistas;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;

class DeportistaTest {

	private static final LocalDate NACIMIENTO = LocalDate.of(2012, 5, 10);

	private final UUID escuelaId = UUID.randomUUID();

	private Deportista deportista() {
		return Deportista.crear(escuelaId, "Juan", "Perez", "30111222", "20301112220", NACIMIENTO, "Argentina",
				"Calle 1", "Piso 2", "Banfield", "Lomas", "1828", "11-5555-0000", "juan@example.com");
	}

	@Test
	void crearDejaElDeportistaActivoEnLaEscuelaIndicadaConTodosSusDatos() {
		Deportista d = deportista();

		assertThat(d.getEscuelaId()).isEqualTo(escuelaId);
		assertThat(d.isActivo()).isTrue();
		assertThat(d.getNombre()).isEqualTo("Juan");
		assertThat(d.getApellido()).isEqualTo("Perez");
		assertThat(d.getDni()).isEqualTo("30111222");
		assertThat(d.getCuil()).isEqualTo("20301112220");
		assertThat(d.getFechaNacimiento()).isEqualTo(NACIMIENTO);
		assertThat(d.getNacionalidad()).isEqualTo("Argentina");
		assertThat(d.getDomicilio()).isEqualTo("Calle 1");
		assertThat(d.getOtrosDatosDomicilio()).isEqualTo("Piso 2");
		assertThat(d.getLocalidad()).isEqualTo("Banfield");
		assertThat(d.getPartido()).isEqualTo("Lomas");
		assertThat(d.getCodigoPostal()).isEqualTo("1828");
		assertThat(d.getTelefonoContacto()).isEqualTo("11-5555-0000");
		assertThat(d.getEmailFederativo()).isEqualTo("juan@example.com");
	}

	@Test
	void crearConSoloLosObligatoriosDejaLosOpcionalesNulos() {
		Deportista d = Deportista.crear(escuelaId, "Juan", "Perez", "30111222", null, null, null, null, null, null, null,
				null, null, null);

		assertThat(d.getCuil()).isNull();
		assertThat(d.getFechaNacimiento()).isNull();
		assertThat(d.getEmailFederativo()).isNull();
		assertThat(d.isActivo()).isTrue();
	}

	@Test
	void actualizarConLosMismosDatosNoDevuelveCambios() {
		assertThat(deportista().actualizar("Juan", "Perez", "30111222", "20301112220", NACIMIENTO, "Argentina", "Calle 1",
				"Piso 2", "Banfield", "Lomas", "1828", "11-5555-0000", "juan@example.com")).isEmpty();
	}

	@Test
	void actualizarDevuelveSoloLosNombresDeLosCamposCambiadosYNoSusValores() {
		Deportista d = deportista();

		var cambios = d.actualizar("Juan", "Perez", "30999888", "20301112220", NACIMIENTO, "Argentina", "Calle 1",
				"Piso 2", "Temperley", "Lomas", "1828", "11-5555-0000", "juan@example.com");

		assertThat(cambios).containsExactly("dni", "localidad");
		assertThat(d.getDni()).isEqualTo("30999888");
		assertThat(d.getLocalidad()).isEqualTo("Temperley");
		assertThat(cambios).noneMatch(c -> c.contains("30999888") || c.contains("Temperley"));
	}

	@Test
	void actualizarConOpcionalesNulosLosBorraYLoInformaPorNombre() {
		Deportista d = deportista();

		var cambios = d.actualizar("Juan", "Perez", "30111222", null, null, null, null, null, null, null, null, null,
				null);

		assertThat(cambios).containsExactly("cuil", "fechaNacimiento", "nacionalidad", "domicilio",
				"otrosDatosDomicilio", "localidad", "partido", "codigoPostal", "telefonoContacto", "emailFederativo");
		assertThat(d.getCuil()).isNull();
	}

	@Test
	void actualizarNoCambiaElEstado() {
		Deportista d = deportista();
		d.desactivar();

		d.actualizar("Otro", "Perez", "30111222", null, null, null, null, null, null, null, null, null, null);

		assertThat(d.isActivo()).isFalse();
	}

	@Test
	void activarYDesactivarDevuelvenTrueSoloCuandoElEstadoCambia() {
		Deportista d = deportista();

		assertThat(d.activar()).isFalse();
		assertThat(d.desactivar()).isTrue();
		assertThat(d.isActivo()).isFalse();
		assertThat(d.desactivar()).isFalse();
		assertThat(d.activar()).isTrue();
		assertThat(d.isActivo()).isTrue();
	}

	@Test
	void laEscuelaEsInmutableEnLaBase() throws Exception {
		Field campo = Deportista.class.getDeclaredField("escuelaId");

		assertThat(campo.getAnnotation(Column.class).updatable()).isFalse();
	}

	@Test
	void noTieneSettersNiAsociacionesNiCamposDeTemporada() {
		assertThat(Arrays.stream(Deportista.class.getDeclaredMethods()).map(m -> m.getName()))
				.noneMatch(n -> n.startsWith("set"));
		assertThat(Arrays.stream(Deportista.class.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers()))
				.map(Field::getName)).containsExactlyInAnyOrder("id", "escuelaId", "nombre", "apellido", "dni", "cuil",
						"fechaNacimiento", "nacionalidad", "domicilio", "otrosDatosDomicilio", "localidad", "partido",
						"codigoPostal", "telefonoContacto", "emailFederativo", "activo");
	}
}
