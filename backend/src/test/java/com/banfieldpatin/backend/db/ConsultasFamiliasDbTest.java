package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.web.Busqueda;
import com.banfieldpatin.backend.compartido.web.FiltroEstado;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.Familia;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.dto.FamiliaAdminResumen;
import com.banfieldpatin.backend.familias.dto.FamiliaDetalle;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.persistence.EntityManagerFactory;

/**
 * Ejecuta de verdad {@code FamiliaRepository.buscar} y el servicio de listado/detalle contra PostgreSQL: busqueda sin
 * distinguir mayusculas, comodines de LIKE, orden, filtro de estado, paginacion, aislamiento por escuela y cantidad de
 * sentencias de Hibernate (con mas de 5 filas sembradas, para que un N+1 se note).
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class ConsultasFamiliasDbTest extends BaseDbTest {

	@Autowired
	JdbcClient jdbc;
	@Autowired
	FamiliaRepository familias;
	@Autowired
	FamiliaAdminService servicio;
	@Autowired
	TutorRepository tutores;
	@Autowired
	EntityManagerFactory emf;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UsuarioAutenticado adminA;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("fam-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("fam-b-" + UUID.randomUUID());
		adminA = new UsuarioAutenticado(datos.admin(escuelaA, "admin@a.example", true), escuelaA, null, Rol.ADMIN);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private List<String> nombres(FiltroEstado estado, String busqueda) {
		return familias.buscar(escuelaA, estado.name(), Busqueda.patronLike(busqueda), PageRequest.of(0, 50))
				.getContent().stream().map(Familia::getNombreReferencia).toList();
	}

	@Test
	void filtraPorEstadoYSoloDevuelveLaEscuelaIndicada() {
		datos.familia(escuelaA, "Activa Uno", true);
		datos.familia(escuelaA, "Inactiva Dos", false);
		datos.familia(escuelaB, "De Otra Escuela", true);

		assertThat(nombres(FiltroEstado.TODOS, "")).containsExactly("Activa Uno", "Inactiva Dos");
		assertThat(nombres(FiltroEstado.ACTIVOS, "")).containsExactly("Activa Uno");
		assertThat(nombres(FiltroEstado.INACTIVOS, "")).containsExactly("Inactiva Dos");
	}

	@Test
	void busquedaContieneSinDistinguirMayusculas() {
		datos.familia(escuelaA, "Perez", true);
		datos.familia(escuelaA, "Los PEREZ Gomez", true);
		datos.familia(escuelaA, "Gomez", true);

		assertThat(nombres(FiltroEstado.TODOS, "per")).containsExactly("Los PEREZ Gomez", "Perez");
		assertThat(nombres(FiltroEstado.TODOS, "  PEREZ  ")).containsExactly("Los PEREZ Gomez", "Perez");
		assertThat(nombres(FiltroEstado.TODOS, "zzz")).isEmpty();
	}

	@Test
	void losComodinesDeLikeSeTratanComoLiterales() {
		datos.familia(escuelaA, "Cien%Por", true);
		datos.familia(escuelaA, "Guion_Bajo", true);
		datos.familia(escuelaA, "Admiracion!", true);
		datos.familia(escuelaA, "Cienxx", true);

		assertThat(nombres(FiltroEstado.TODOS, "%")).containsExactly("Cien%Por");
		assertThat(nombres(FiltroEstado.TODOS, "_")).containsExactly("Guion_Bajo");
		assertThat(nombres(FiltroEstado.TODOS, "!")).containsExactly("Admiracion!");
		assertThat(nombres(FiltroEstado.TODOS, "n_B")).containsExactly("Guion_Bajo");
	}

	@Test
	void ordenaPorNombreSinDistinguirMayusculasYDesempataPorId() {
		datos.familia(escuelaA, "banfield", true);
		datos.familia(escuelaA, "Zarate", true);
		datos.familia(escuelaA, "Alvarez", true);
		UUID gemelaUno = datos.familia(escuelaA, "Mismo", true);
		UUID gemelaDos = datos.familia(escuelaA, "Mismo", true);

		Page<Familia> pagina = familias.buscar(escuelaA, "TODOS", "", PageRequest.of(0, 50));

		assertThat(pagina.getContent().stream().map(Familia::getNombreReferencia))
				.containsExactly("Alvarez", "banfield", "Mismo", "Mismo", "Zarate");
		// PostgreSQL ordena uuid byte a byte (equivale al orden del texto hexadecimal); UUID.compareTo de Java firma los longs.
		List<String> gemelas = pagina.getContent().stream().filter(f -> "Mismo".equals(f.getNombreReferencia()))
				.map(f -> f.getId().toString()).toList();
		assertThat(gemelas).containsExactlyElementsOf(
				List.of(gemelaUno.toString(), gemelaDos.toString()).stream().sorted().toList());
	}

	@Test
	void paginaYTotalesConLaCuentaDeLaConsultaExplicita() {
		for (int i = 0; i < 25; i++) {
			datos.familia(escuelaA, String.format("Familia %02d", i), true);
		}

		Page<Familia> primera = familias.buscar(escuelaA, "TODOS", "", Pagina.pedir(0, 20));
		Page<Familia> segunda = familias.buscar(escuelaA, "TODOS", "", Pagina.pedir(1, 20));
		Page<Familia> acotada = familias.buscar(escuelaA, "TODOS", "", Pagina.pedir(-3, 1000));

		assertThat(primera.getContent()).hasSize(20);
		assertThat(primera.getTotalElements()).isEqualTo(25);
		assertThat(segunda.getContent()).hasSize(5);
		assertThat(acotada.getNumber()).isZero();
		assertThat(acotada.getSize()).isEqualTo(100);
		assertThat(acotada.getContent()).hasSize(25);
	}

	@Test
	void elListadoLegacyDeActivasNoCambia() {
		datos.familia(escuelaA, "Activa", true);
		datos.familia(escuelaA, "Inactiva", false);

		List<String> activas = familias.buscarActivas(escuelaA, "", PageRequest.of(0, 20)).getContent().stream()
				.map(Familia::getNombreReferencia).toList();

		assertThat(activas).containsExactly("Activa");
	}

	@Test
	void findByIdAndEscuelaIdNoDevuelveFamiliasDeOtraEscuela() {
		UUID propia = datos.familia(escuelaA, "Propia", true);
		UUID ajena = datos.familia(escuelaB, "Ajena", true);

		assertThat(familias.findByIdAndEscuelaId(propia, escuelaA)).isPresent();
		assertThat(familias.findByIdAndEscuelaId(ajena, escuelaA)).isEmpty();
		assertThat(familias.findByIdAndEscuelaId(propia, escuelaB)).isEmpty();
	}

	// ---------- sentencias de Hibernate (sin N+1) ----------

	private long sentencias(Runnable accion) {
		var estadisticas = emf.unwrap(SessionFactory.class).getStatistics();
		estadisticas.clear();
		accion.run();
		return estadisticas.getPrepareStatementCount();
	}

	@Test
	void elListadoUsaComoMaximoCuatroSentenciasSinImportarCuantasFilasHay() {
		for (int i = 0; i < 6; i++) {
			datos.familia(escuelaA, "Familia " + i, i % 2 == 0);
		}
		long conSeis = sentencias(() -> servicio.listar(adminA, FiltroEstado.TODOS, "", Pagina.pedir(0, 20)));
		for (int i = 6; i < 15; i++) {
			datos.familia(escuelaA, "Familia " + i, true);
		}
		long conQuince = sentencias(() -> servicio.listar(adminA, FiltroEstado.TODOS, "", Pagina.pedir(0, 20)));

		// Pagina + cuenta + tutores agrupados + deportistas activos agrupados (techo 4, REQ-XC-04).
		assertThat(conSeis).isLessThanOrEqualTo(4);
		assertThat(conQuince).isEqualTo(conSeis);
	}

	@Test
	void elListadoConTutoresSigueEnElTechoDeSentenciasYCuentaCadaFamiliaPorSeparado() {
		List<UUID> familiasIds = new java.util.ArrayList<>();
		for (int i = 0; i < 6; i++) {
			UUID f = datos.familia(escuelaA, "Familia " + i, i % 2 == 0);
			familiasIds.add(f);
			// La familia i tiene i tutores (0 a 5): un conteo distinto por fila delata un N+1 o un mezclado.
			for (int t = 0; t < i; t++) {
				datos.tutor(escuelaA, f, "Tutor" + t, "De" + i);
			}
		}
		UUID ajena = datos.familia(escuelaB, "Ajena", true);
		datos.tutor(escuelaB, ajena, "Ajeno", "Ajeno");

		long conSeis = sentencias(() -> servicio.listar(adminA, FiltroEstado.TODOS, "", Pagina.pedir(0, 20)));
		Pagina<FamiliaAdminResumen> pagina = servicio.listar(adminA, FiltroEstado.TODOS, "", Pagina.pedir(0, 20));

		assertThat(conSeis).isLessThanOrEqualTo(4);
		assertThat(pagina.contenido()).extracting(FamiliaAdminResumen::nombreReferencia, FamiliaAdminResumen::cantidadTutores)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("Familia 0", 0L),
						org.assertj.core.groups.Tuple.tuple("Familia 1", 1L),
						org.assertj.core.groups.Tuple.tuple("Familia 2", 2L),
						org.assertj.core.groups.Tuple.tuple("Familia 3", 3L),
						org.assertj.core.groups.Tuple.tuple("Familia 4", 4L),
						org.assertj.core.groups.Tuple.tuple("Familia 5", 5L));
		assertThat(pagina.contenido()).allSatisfy(r -> assertThat(r.cantidadDeportistasActivos()).isZero());

		// Mas filas y mas tutores no suman sentencias.
		for (int i = 6; i < 12; i++) {
			UUID f = datos.familia(escuelaA, "Familia " + i, true);
			datos.tutor(escuelaA, f, "Tutor", "De" + i);
		}
		assertThat(sentencias(() -> servicio.listar(adminA, FiltroEstado.TODOS, "", Pagina.pedir(0, 20))))
				.isEqualTo(conSeis);
	}

	@Test
	void laConsultaAgrupadaNoCuentaTutoresDeOtraEscuelaNiDeFamiliasNoPedidas() {
		UUID propia = datos.familia(escuelaA, "Propia", true);
		UUID otraPropia = datos.familia(escuelaA, "OtraPropia", true);
		UUID ajena = datos.familia(escuelaB, "Ajena", true);
		datos.tutor(escuelaA, propia, "Ana", "Uno");
		datos.tutor(escuelaA, propia, "Luis", "Uno");
		datos.tutor(escuelaA, otraPropia, "Eva", "Dos");
		datos.tutor(escuelaB, ajena, "Ajeno", "Ajeno");

		var conteos = tutores.contarPorFamilia(escuelaA, List.of(propia, ajena));

		assertThat(conteos).singleElement().satisfies(c -> {
			assertThat(c.familiaId()).isEqualTo(propia);
			assertThat(c.cantidad()).isEqualTo(2);
		});
		assertThat(tutores.contarPorFamilia(escuelaA, List.of(ajena))).isEmpty();
	}

	@Test
	void elDetalleUsaComoMaximoDosSentenciasConCincoOMasTutores() {
		UUID id = null;
		for (int i = 0; i < 6; i++) {
			id = datos.familia(escuelaA, "Familia " + i, true);
		}
		UUID elegida = id;
		for (int t = 0; t < 6; t++) {
			datos.tutor(escuelaA, elegida, "Tutor" + t, "Apellido");
		}

		assertThat(sentencias(() -> servicio.obtener(adminA, elegida))).isLessThanOrEqualTo(2);
		assertThat(servicio.obtener(adminA, elegida).tutores()).hasSize(6);
	}

	@Test
	void elDetalleDeUnaFamiliaInactivaDevuelveSusTutoresOrdenadosSinLosDeOtrasFamilias() {
		UUID inactiva = datos.familia(escuelaA, "Inactiva", false);
		UUID otra = datos.familia(escuelaA, "Otra", true);
		datos.tutor(escuelaA, inactiva, "Zoe", "Gomez");
		datos.tutor(escuelaA, inactiva, "Beto", "Alvarez");
		datos.tutor(escuelaA, otra, "Otro", "Otro");

		FamiliaDetalle detalle = servicio.obtener(adminA, inactiva);

		assertThat(detalle.activa()).isFalse();
		assertThat(detalle.tutores()).extracting(t -> t.nombre()).containsExactly("Beto", "Zoe");
		assertThat(detalle.tutores()).allSatisfy(t -> {
			assertThat(t.familiaId()).isEqualTo(inactiva);
			assertThat(t.activo()).isTrue();
		});
	}

	@Test
	void elDetalleDeUnaFamiliaAjenaNoDevuelveNadaNiSusTutores() {
		UUID ajena = datos.familia(escuelaB, "Ajena", true);
		datos.tutor(escuelaB, ajena, "Ajeno", "Ajeno");

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> servicio.obtener(adminA, ajena))
				.isInstanceOfSatisfying(com.banfieldpatin.backend.compartido.error.ExcepcionNegocio.class,
						e -> assertThat(e.getCodigo()).isEqualTo("FAMILIA_NO_ENCONTRADA"));
	}
}
