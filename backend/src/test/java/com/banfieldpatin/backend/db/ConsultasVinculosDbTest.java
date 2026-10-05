package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.FiltroEstado;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.dto.FamiliaAdminResumen;
import com.banfieldpatin.backend.familias.vinculos.EstadoVinculo;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta;

/**
 * Consultas de vinculos y del conteo de deportistas activos por familia contra PostgreSQL real: techos de sentencias de
 * Hibernate con mas de 5 filas sembradas (un N+1 se nota), listados con todos los estados, aislamiento por escuela y
 * el conteo N2 del listado de familias (vinculo ACTIVO a deportista activo).
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class ConsultasVinculosDbTest extends BaseVinculosDb {

	@org.springframework.beans.factory.annotation.Autowired
	FamiliaAdminService familiasServicio;

	private List<UUID> sembrarDeportistas(int cantidad, String prefijo) {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < cantidad; i++) {
			ids.add(deportista(prefijo + i));
		}
		return ids;
	}

	// ---------- listados ----------

	@Test
	void listarLosVinculosDeUnaFamiliaUsaComoMaximoDosSentenciasConMasDeCincoFilasYIncluyeCadaEstado() {
		UUID familia = datos.familia(escuelaA, "Perez", true);
		List<UUID> activos = sembrarDeportistas(6, "Act");
		activos.forEach(d -> datos.vinculo(escuelaA, familia, d, "ACTIVO", false, adminId));
		datos.vinculo(escuelaA, familia, deportista("Rev"), "REVOCADO", false, adminId);
		datos.vinculo(escuelaA, familia, deportista("Pen"), "PENDIENTE", false, null);
		datos.vinculo(escuelaA, familia, deportista("Rec"), "RECHAZADO", false, null);

		List<VinculoRespuesta>[] lista = new List[1];
		long sentencias = sentencias(() -> lista[0] = servicio.listarDeFamilia(admin, familia));

		assertThat(sentencias).isLessThanOrEqualTo(2);
		assertThat(lista[0]).hasSize(9);
		assertThat(lista[0]).extracting(VinculoRespuesta::estado).contains(EstadoVinculo.ACTIVO, EstadoVinculo.REVOCADO,
				EstadoVinculo.PENDIENTE, EstadoVinculo.RECHAZADO);
		assertThat(lista[0]).allSatisfy(v -> assertThat(v.familiaId()).isEqualTo(familia));
		// Orden por apellido, nombre e id (los apellidos de los datos de prueba son distintos).
		assertThat(lista[0]).extracting(VinculoRespuesta::deportistaApellido)
				.isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
	}

	@Test
	void listarLasFamiliasDeUnDeportistaUsaComoMaximoDosSentenciasConMasDeCincoFilasYIncluyeCadaEstado() {
		UUID d = deportista("Uno");
		for (int i = 0; i < 6; i++) {
			UUID f = datos.familia(escuelaA, "Familia " + i, true);
			datos.vinculo(escuelaA, f, d, i == 0 ? "ACTIVO" : "REVOCADO", i == 0, adminId);
		}
		datos.vinculo(escuelaA, datos.familia(escuelaA, "Pendiente", true), d, "PENDIENTE", false, null);

		List<VinculoRespuesta>[] lista = new List[1];
		long sentencias = sentencias(() -> lista[0] = servicio.listarDeDeportista(admin, d));

		assertThat(sentencias).isLessThanOrEqualTo(2);
		assertThat(lista[0]).hasSize(7);
		assertThat(lista[0]).extracting(VinculoRespuesta::deportistaId).containsOnly(d);
		assertThat(lista[0]).extracting(VinculoRespuesta::familiaNombre).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
	}

	@Test
	void unIdDeOtraEscuelaDa404IdenticoAUnIdAleatorioEnAmbosListados() {
		UUID familiaB = datos.familia(escuelaB, "De B", true);
		UUID deportistaB = deportista(escuelaB, "DeB", true);
		datos.vinculo(escuelaB, familiaB, deportistaB, "ACTIVO", true, adminDeB.id());

		Throwable familiaAjena = catchThrowable(() -> servicio.listarDeFamilia(admin, familiaB));
		Throwable familiaAleatoria = catchThrowable(() -> servicio.listarDeFamilia(admin, UUID.randomUUID()));
		Throwable deportistaAjeno = catchThrowable(() -> servicio.listarDeDeportista(admin, deportistaB));
		Throwable deportistaAleatorio = catchThrowable(() -> servicio.listarDeDeportista(admin, UUID.randomUUID()));

		assertThat(familiaAjena).isInstanceOf(ExcepcionNegocio.class).hasMessage(familiaAleatoria.getMessage());
		assertThat(((ExcepcionNegocio) familiaAjena).getCodigo()).isEqualTo("FAMILIA_NO_ENCONTRADA")
				.isEqualTo(((ExcepcionNegocio) familiaAleatoria).getCodigo());
		assertThat(deportistaAjeno).isInstanceOf(ExcepcionNegocio.class).hasMessage(deportistaAleatorio.getMessage());
		assertThat(((ExcepcionNegocio) deportistaAjeno).getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO")
				.isEqualTo(((ExcepcionNegocio) deportistaAleatorio).getCodigo());
		// Y el propio admin de B si los ve.
		assertThat(servicio.listarDeFamilia(adminDeB, familiaB)).hasSize(1);
	}

	@Test
	void losListadosNuncaMezclanVinculosDeOtraFamiliaODeOtraEscuela() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");
		datos.vinculo(escuelaA, f1, d, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, f2, d, "ACTIVO", false, adminId);
		UUID familiaB = datos.familia(escuelaB, "De B", true);
		datos.vinculo(escuelaB, familiaB, deportista(escuelaB, "DeB", true), "ACTIVO", true, adminDeB.id());

		assertThat(servicio.listarDeFamilia(admin, f1)).extracting(VinculoRespuesta::familiaId).containsOnly(f1);
		assertThat(servicio.listarDeDeportista(admin, d)).extracting(VinculoRespuesta::familiaId)
				.containsExactlyInAnyOrder(f1, f2);
	}

	// ---------- escrituras: techos de sentencias ----------

	@Test
	void vincularUnLoteDeNUsaComoMaximoCuatroMasDosNSentenciasConMasDeCincoFilas() {
		UUID familia = datos.familia(escuelaA, "Perez", true);
		List<UUID> ids = sembrarDeportistas(7, "Lote");
		// Mezcla de resultados: dos ya ACTIVOS (SIN_CAMBIOS), uno REVOCADO (REACTIVADO) y cuatro nuevos (CREADO).
		datos.vinculo(escuelaA, familia, ids.get(0), "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, familia, ids.get(1), "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, familia, ids.get(2), "REVOCADO", false, adminId);

		long sentencias = sentencias(() -> servicio.vincular(admin, familia, ids, DATOS));

		assertThat(sentencias).isLessThanOrEqualTo(4 + 2L * ids.size());
		// Solo Hibernate: familia + bloqueo + existentes + principales + una escritura por vinculo cambiado (5).
		assertThat(sentencias).isLessThanOrEqualTo(4 + 5);
		assertThat(contarAuditoria("VINCULO_ACTIVADO")).isEqualTo(5);
		verificarInvariantes();
	}

	@Test
	void cambiarElPrincipalUsaComoMaximoSeisSentenciasDeHibernateConMasDeCincoFamilias() {
		UUID d = deportista("Uno");
		List<UUID> familias = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			UUID f = datos.familia(escuelaA, "Familia " + i, true);
			familias.add(f);
			servicio.vincular(admin, f, List.of(d), DATOS);
		}

		long sentencias = sentencias(() -> servicio.cambiarPrincipal(admin, familias.get(5), d, DATOS));

		// familia + bloqueo + vinculo objetivo + principal actual + bajar + subir = 6 (mas la auditoria por JDBC).
		assertThat(sentencias).isLessThanOrEqualTo(6);
		assertThat(principalesActivos(d)).isEqualTo(1);
	}

	@Test
	void revocarUsaComoMaximoCincoSentencias() {
		UUID f = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f, List.of(d), DATOS);

		long sentencias = sentencias(() -> servicio.revocar(admin, f, d, DATOS));

		assertThat(sentencias).isLessThanOrEqualTo(5);
	}

	// ---------- cantidadDeportistasActivos (N2) en el listado de familias ----------

	@Test
	void elListadoDeFamiliasUsaComoMaximoCuatroSentenciasConMasDeCincoFamiliasYNoDependeDeLasFilas() {
		for (int i = 0; i < 6; i++) {
			UUID f = datos.familia(escuelaA, "Familia " + i, true);
			datos.tutor(escuelaA, f, "Tutor", "De" + i);
			for (int j = 0; j <= i; j++) {
				datos.vinculo(escuelaA, f, deportista("F" + i + "D" + j), "ACTIVO", false, adminId);
			}
		}

		long conSeis = sentencias(() -> familiasServicio.listar(admin, FiltroEstado.TODOS, "", Pagina.pedir(0, 20)));
		for (int i = 6; i < 12; i++) {
			UUID f = datos.familia(escuelaA, "Familia " + i, true);
			datos.vinculo(escuelaA, f, deportista("G" + i), "ACTIVO", false, adminId);
		}
		long conDoce = sentencias(() -> familiasServicio.listar(admin, FiltroEstado.TODOS, "", Pagina.pedir(0, 20)));

		assertThat(conSeis).isLessThanOrEqualTo(4);
		assertThat(conDoce).isEqualTo(conSeis);
	}

	@Test
	void cantidadDeportistasActivosCuentaSoloVinculosActivosDeDeportistasActivosYNoTocaLosVinculos() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID f3 = datos.familia(escuelaA, "Tres", true);
		UUID a = deportista("A");
		UUID b = deportista("B");
		UUID inactivo = deportista(escuelaA, "Inactivo", false);
		UUID revocado = deportista("Revocado");
		UUID pendiente = deportista("Pendiente");
		// Familia 1: dos ACTIVOS validos + uno a un deportista inactivo + REVOCADO + PENDIENTE => 2.
		datos.vinculo(escuelaA, f1, a, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, f1, b, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, f1, inactivo, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, f1, revocado, "REVOCADO", false, adminId);
		datos.vinculo(escuelaA, f1, pendiente, "PENDIENTE", false, null);
		// Familia 2: el mismo deportista A tambien vinculado (cuenta en ambas familias): 1. Familia 3: ninguno: 0.
		datos.vinculo(escuelaA, f2, a, "ACTIVO", false, adminId);

		assertThat(cantidades()).containsEntry(f1, 2L).containsEntry(f2, 1L).containsEntry(f3, 0L);

		// Desactivar un deportista baja el conteo sin tocar el vinculo; revocar otro vinculo tambien lo baja.
		jdbc.sql("UPDATE gestion_patin.deportista SET activo = false WHERE id = :id").param("id", b).update();
		assertThat(cantidades()).containsEntry(f1, 1L);
		assertThat(filaEstado(f1, b)).isEqualTo("ACTIVO");
		servicio.revocar(admin, f1, a, DATOS);
		assertThat(cantidades()).containsEntry(f1, 0L).containsEntry(f2, 1L);
	}

	private java.util.Map<UUID, Long> cantidades() {
		java.util.Map<UUID, Long> mapa = new java.util.HashMap<>();
		for (FamiliaAdminResumen r : familiasServicio.listar(admin, FiltroEstado.TODOS, "", Pagina.pedir(0, 20)).contenido()) {
			mapa.put(r.id(), r.cantidadDeportistasActivos());
		}
		return mapa;
	}

	private String filaEstado(UUID familia, UUID deportista) {
		return (String) fila(familia, deportista).orElseThrow().get("estado");
	}

	@Test
	void elConteoNoMezclaVinculosDeOtraEscuela() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID familiaB = datos.familia(escuelaB, "De B", true);
		datos.vinculo(escuelaB, familiaB, deportista(escuelaB, "DeB", true), "ACTIVO", true, adminDeB.id());
		datos.vinculo(escuelaA, f1, deportista("A"), "ACTIVO", true, adminId);

		assertThat(repositorio.contarActivosPorFamilia(escuelaA, List.of(f1, familiaB))).singleElement()
				.satisfies(c -> assertThat(c.familiaId()).isEqualTo(f1));
	}
}
