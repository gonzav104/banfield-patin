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
	void elListadoUsaDosSentenciasSinImportarCuantasFilasHay() {
		for (int i = 0; i < 6; i++) {
			datos.familia(escuelaA, "Familia " + i, i % 2 == 0);
		}
		long conSeis = sentencias(() -> servicio.listar(adminA, FiltroEstado.TODOS, "", Pagina.pedir(0, 20)));
		for (int i = 6; i < 15; i++) {
			datos.familia(escuelaA, "Familia " + i, true);
		}
		long conQuince = sentencias(() -> servicio.listar(adminA, FiltroEstado.TODOS, "", Pagina.pedir(0, 20)));

		// Pagina + cuenta. Cuando existan tutores y vinculos se sumaran dos agrupadas (techo 4, REQ-XC-04).
		assertThat(conSeis).isLessThanOrEqualTo(2);
		assertThat(conQuince).isEqualTo(conSeis);
	}

	@Test
	void elDetalleUsaUnaSolaSentencia() {
		UUID id = null;
		for (int i = 0; i < 6; i++) {
			id = datos.familia(escuelaA, "Familia " + i, true);
		}
		UUID elegida = id;

		assertThat(sentencias(() -> servicio.obtener(adminA, elegida))).isEqualTo(1);
	}
}
