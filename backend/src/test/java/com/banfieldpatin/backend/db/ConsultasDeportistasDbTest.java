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
import org.springframework.transaction.support.TransactionTemplate;

import com.banfieldpatin.backend.compartido.web.Busqueda;
import com.banfieldpatin.backend.compartido.web.FiltroEstado;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.deportistas.Deportista;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.deportistas.dto.DeportistaResumen;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.persistence.EntityManagerFactory;

/**
 * Ejecuta de verdad {@code DeportistaRepository} y el servicio de listado/detalle contra PostgreSQL: busqueda por
 * apellido, nombre y nombre completo en ambos ordenes, prefijo de DNI con normalizacion, comodines de LIKE, orden,
 * filtro de estado, paginacion, aislamiento por escuela, comprobaciones de unicidad y cantidad de sentencias de
 * Hibernate (con mas de 5 filas sembradas, para que un N+1 se note).
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class ConsultasDeportistasDbTest extends BaseDbTest {

	@Autowired
	JdbcClient jdbc;
	@Autowired
	DeportistaRepository deportistas;
	@Autowired
	DeportistaAdminService servicio;
	@Autowired
	TransactionTemplate transaccion;
	@Autowired
	EntityManagerFactory emf;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UsuarioAutenticado adminA;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("dcons-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("dcons-b-" + UUID.randomUUID());
		adminA = new UsuarioAutenticado(datos.admin(escuelaA, "admin@a.example", true), escuelaA, null, Rol.ADMIN);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	/** Busca por texto como lo hace el servicio: patron escapado y digitos del DNI. */
	private List<String> nombres(FiltroEstado estado, String busqueda) {
		return deportistas.buscar(escuelaA, estado.name(), Busqueda.patronLike(busqueda), Busqueda.digitos(busqueda),
				PageRequest.of(0, 50)).getContent().stream().map(d -> d.getApellido() + " " + d.getNombre()).toList();
	}

	private void sembrarBase() {
		datos.deportista(escuelaA, "12345678", "Juan", "Perez");
		datos.deportista(escuelaA, "23456789", "Ana", "Perez", false);
		datos.deportista(escuelaA, "34567890", "Luis", "Gomez");
		datos.deportista(escuelaB, "12345678", "Juan", "Perez");
	}

	// ---------- estado, escuela y busqueda ----------

	@Test
	void sinParametrosEnActivosDevuelveSoloLosActivosDeLaEscuelaOrdenados() {
		sembrarBase();

		assertThat(nombres(FiltroEstado.ACTIVOS, "")).containsExactly("Gomez Luis", "Perez Juan");
		assertThat(nombres(FiltroEstado.INACTIVOS, "")).containsExactly("Perez Ana");
		assertThat(nombres(FiltroEstado.TODOS, "")).containsExactly("Gomez Luis", "Perez Ana", "Perez Juan");
	}

	@Test
	void busquedaPorApellidoNombreYNombreCompletoEnAmbosOrdenesSinDistinguirMayusculas() {
		sembrarBase();

		assertThat(nombres(FiltroEstado.TODOS, "perez")).containsExactly("Perez Ana", "Perez Juan");
		assertThat(nombres(FiltroEstado.TODOS, "  PEREZ ")).containsExactly("Perez Ana", "Perez Juan");
		assertThat(nombres(FiltroEstado.TODOS, "jua")).containsExactly("Perez Juan");
		assertThat(nombres(FiltroEstado.TODOS, "juan perez")).containsExactly("Perez Juan");
		assertThat(nombres(FiltroEstado.TODOS, "perez juan")).containsExactly("Perez Juan");
		assertThat(nombres(FiltroEstado.TODOS, "JUAN PE")).containsExactly("Perez Juan");
		assertThat(nombres(FiltroEstado.TODOS, "ana pe")).containsExactly("Perez Ana");
		assertThat(nombres(FiltroEstado.TODOS, "zzz")).isEmpty();
		assertThat(nombres(FiltroEstado.ACTIVOS, "perez")).containsExactly("Perez Juan");
	}

	@Test
	void busquedaPorPrefijoDeDniConYSinPuntosYEspaciosNormalizados() {
		sembrarBase();

		assertThat(nombres(FiltroEstado.TODOS, "12345")).containsExactly("Perez Juan");
		assertThat(nombres(FiltroEstado.TODOS, "12.345")).containsExactly("Perez Juan");
		assertThat(nombres(FiltroEstado.TODOS, "12 345")).containsExactly("Perez Juan");
		assertThat(nombres(FiltroEstado.TODOS, "12.345.678")).containsExactly("Perez Juan");
		assertThat(nombres(FiltroEstado.TODOS, "2345")).containsExactly("Perez Ana");
		assertThat(nombres(FiltroEstado.TODOS, "3")).containsExactly("Gomez Luis");
		// Solo prefijo: un tramo del medio del DNI no coincide.
		assertThat(nombres(FiltroEstado.TODOS, "345")).containsExactly("Gomez Luis");
		assertThat(nombres(FiltroEstado.TODOS, "45678")).isEmpty();
		assertThat(nombres(FiltroEstado.INACTIVOS, "2345")).containsExactly("Perez Ana");
		assertThat(nombres(FiltroEstado.ACTIVOS, "2345")).isEmpty();
	}

	@Test
	void unaBusquedaConLetrasNoBuscaPorDniAunqueContengaDigitos() {
		datos.deportista(escuelaA, "12345678", "Juan", "Perez");
		datos.deportista(escuelaA, "99999999", "Beto2", "Gomez");

		// "12345a" no es todo digitos: solo se busca por nombre (nadie se llama asi).
		assertThat(nombres(FiltroEstado.TODOS, "12345a")).isEmpty();
		assertThat(nombres(FiltroEstado.TODOS, "beto2")).containsExactly("Gomez Beto2");
	}

	@Test
	void losComodinesDeLikeSeTratanComoLiterales() {
		datos.deportista(escuelaA, "30000001", "Cien%Por", "Uno");
		datos.deportista(escuelaA, "30000002", "Guion_Bajo", "Dos");
		datos.deportista(escuelaA, "30000003", "Admiracion!", "Tres");
		datos.deportista(escuelaA, "30000004", "Cienxx", "Cuatro");

		assertThat(nombres(FiltroEstado.TODOS, "%")).containsExactly("Uno Cien%Por");
		assertThat(nombres(FiltroEstado.TODOS, "_")).containsExactly("Dos Guion_Bajo");
		assertThat(nombres(FiltroEstado.TODOS, "!")).containsExactly("Tres Admiracion!");
		assertThat(nombres(FiltroEstado.TODOS, "n_B")).containsExactly("Dos Guion_Bajo");
		assertThat(nombres(FiltroEstado.TODOS, "Cien%")).containsExactly("Uno Cien%Por");
	}

	@Test
	void ordenaPorApellidoYNombreSinDistinguirMayusculasYDesempataPorId() {
		datos.deportista(escuelaA, "30000001", "Zoe", "gomez");
		datos.deportista(escuelaA, "30000002", "Ana", "Gomez");
		datos.deportista(escuelaA, "30000003", "beto", "Alvarez");
		UUID gemeloUno = datos.deportista(escuelaA, "30000004", "Igual", "Igual");
		UUID gemeloDos = datos.deportista(escuelaA, "30000005", "Igual", "Igual");

		Page<Deportista> pagina = deportistas.buscar(escuelaA, "TODOS", "", "", PageRequest.of(0, 50));

		assertThat(pagina.getContent().stream().map(d -> d.getApellido() + " " + d.getNombre()))
				.containsExactly("Alvarez beto", "Gomez Ana", "gomez Zoe", "Igual Igual", "Igual Igual");
		// PostgreSQL ordena uuid byte a byte (equivale al orden del texto hexadecimal); UUID.compareTo de Java firma los longs.
		List<String> gemelos = pagina.getContent().stream().filter(d -> "Igual".equals(d.getApellido()))
				.map(d -> d.getId().toString()).toList();
		assertThat(gemelos).containsExactlyElementsOf(
				List.of(gemeloUno.toString(), gemeloDos.toString()).stream().sorted().toList());
	}

	@Test
	void paginaYTotalesConLaCuentaDeLaConsultaExplicitaYLaBusquedaFiltraElTotal() {
		for (int i = 0; i < 25; i++) {
			datos.deportista(escuelaA, String.format("301000%02d", i), "Nombre" + String.format("%02d", i), "Apellido");
		}
		datos.deportista(escuelaA, "39999999", "Otro", "Distinto");

		Page<Deportista> primera = deportistas.buscar(escuelaA, "TODOS", "", "", Pagina.pedir(0, 20));
		Page<Deportista> segunda = deportistas.buscar(escuelaA, "TODOS", "", "", Pagina.pedir(1, 20));
		Page<Deportista> acotada = deportistas.buscar(escuelaA, "TODOS", "", "", Pagina.pedir(-3, 1000));
		Page<Deportista> filtrada = deportistas.buscar(escuelaA, "TODOS", "apellido", "", Pagina.pedir(0, 20));

		assertThat(primera.getContent()).hasSize(20);
		assertThat(primera.getTotalElements()).isEqualTo(26);
		assertThat(segunda.getContent()).hasSize(6);
		assertThat(acotada.getNumber()).isZero();
		assertThat(acotada.getSize()).isEqualTo(100);
		assertThat(acotada.getContent()).hasSize(26);
		assertThat(filtrada.getTotalElements()).isEqualTo(25);
		assertThat(primera.getContent().get(0).getApellido()).isEqualTo("Apellido");
	}

	@Test
	void unaEscuelaNuncaVeLosDeportistasDeLaOtraNiPorBusquedaNiPorDni() {
		datos.deportista(escuelaB, "55555555", "Secreto", "Ajeno");
		datos.deportista(escuelaA, "30000001", "Propio", "Mio");

		assertThat(nombres(FiltroEstado.TODOS, "")).containsExactly("Mio Propio");
		assertThat(nombres(FiltroEstado.TODOS, "ajeno")).isEmpty();
		assertThat(nombres(FiltroEstado.TODOS, "5555")).isEmpty();
		assertThat(deportistas.activoPorDni(escuelaA, "55555555")).isEmpty();
		assertThat(deportistas.activoPorDni(escuelaB, "55555555")).contains(true);
		UUID ajeno = datos.deportista(escuelaB, "55555556", "Otro", "Ajeno");
		assertThat(deportistas.findByIdAndEscuelaId(ajeno, escuelaA)).isEmpty();
		assertThat(deportistas.findByIdAndEscuelaId(ajeno, escuelaB)).isPresent();
	}

	// ---------- comprobaciones de unicidad ----------

	@Test
	void activoPorDniDistingueActivoInactivoYAusenteYElDeOtroIgnoraAlPropio() {
		UUID activo = datos.deportista(escuelaA, "30000001", "Uno", "Activo");
		UUID inactivo = datos.deportista(escuelaA, "30000002", "Dos", "Inactivo", false);

		assertThat(deportistas.activoPorDni(escuelaA, "30000001")).contains(true);
		assertThat(deportistas.activoPorDni(escuelaA, "30000002")).contains(false);
		assertThat(deportistas.activoPorDni(escuelaA, "30000003")).isEmpty();
		assertThat(deportistas.activoPorDniDeOtro(escuelaA, "30000001", activo)).isEmpty();
		assertThat(deportistas.activoPorDniDeOtro(escuelaA, "30000001", inactivo)).contains(true);
		assertThat(deportistas.activoPorDniDeOtro(escuelaA, "30000002", activo)).contains(false);
	}

	@Test
	void existeElCuilEnLaEscuelaIgnorandoAlPropioYSinMirarOtrasEscuelas() {
		UUID id = datos.deportistaConCuil(escuelaA, "30000001", "20123456786", "Uno", "Cuil");
		datos.deportistaConCuil(escuelaB, "30000009", "20300000003", "Otro", "Escuela");

		assertThat(deportistas.existsByEscuelaIdAndCuil(escuelaA, "20123456786")).isTrue();
		assertThat(deportistas.existsByEscuelaIdAndCuil(escuelaA, "20300000003")).isFalse();
		assertThat(deportistas.existsByEscuelaIdAndCuilAndIdNot(escuelaA, "20123456786", id)).isFalse();
		assertThat(deportistas.existsByEscuelaIdAndCuilAndIdNot(escuelaA, "20123456786", UUID.randomUUID())).isTrue();
	}

	// ---------- bloqueo para vincular (solo SQL; el comportamiento concurrente es del slice de vinculos) ----------

	@Test
	void bloquearParaVincularDevuelveSoloLosDeLaEscuelaOrdenadosPorId() {
		UUID uno = datos.deportista(escuelaA, "30000001", "Uno", "A");
		UUID dos = datos.deportista(escuelaA, "30000002", "Dos", "B");
		UUID tres = datos.deportista(escuelaA, "30000003", "Tres", "C");
		UUID ajeno = datos.deportista(escuelaB, "30000004", "Ajeno", "D");

		List<UUID> ids = transaccion.execute(estado -> deportistas
				.bloquearParaVincular(escuelaA, List.of(tres, ajeno, uno, dos)).stream().map(Deportista::getId).toList());

		assertThat(ids).hasSize(3).doesNotContain(ajeno);
		// Orden de texto hexadecimal = orden de bytes de PostgreSQL.
		assertThat(ids.stream().map(UUID::toString).toList()).isSorted();
	}

	// ---------- sentencias de Hibernate ----------

	private long sentencias(Runnable accion) {
		var estadisticas = emf.unwrap(SessionFactory.class).getStatistics();
		estadisticas.clear();
		accion.run();
		return estadisticas.getPrepareStatementCount();
	}

	@Test
	void listarUsaComoMaximoDosSentenciasYObtenerUnaConMasDeCincoFilas() {
		for (int i = 0; i < 8; i++) {
			datos.deportista(escuelaA, "3000000" + i, "Nombre" + i, "Apellido" + i);
		}
		UUID id = datos.deportista(escuelaA, "30000099", "Objetivo", "Unico");

		long listar = sentencias(() -> {
			var pagina = servicio.listar(adminA, FiltroEstado.TODOS, "", Pagina.pedir(0, 20));
			assertThat(pagina.contenido()).hasSize(9);
		});
		long buscando = sentencias(() -> {
			var pagina = servicio.listar(adminA, FiltroEstado.ACTIVOS, "30000", Pagina.pedir(0, 3));
			assertThat(pagina.contenido()).hasSize(3);
			assertThat(pagina.totalElementos()).isEqualTo(9);
		});
		long obtener = sentencias(() -> assertThat(servicio.obtener(adminA, id).nombre()).isEqualTo("Objetivo"));

		assertThat(listar).isLessThanOrEqualTo(2);
		assertThat(buscando).isLessThanOrEqualTo(2);
		assertThat(obtener).isLessThanOrEqualTo(1);
	}

	@Test
	void elServicioDevuelveElResumenSinDatosDeOtraEscuelaYConElTotalCorrecto() {
		sembrarBase();

		var pagina = servicio.listar(adminA, FiltroEstado.TODOS, "perez", Pagina.pedir(0, 20));

		assertThat(pagina.totalElementos()).isEqualTo(2);
		assertThat(pagina.contenido()).extracting(DeportistaResumen::nombre).containsExactly("Ana", "Juan");
		assertThat(pagina.contenido()).extracting(DeportistaResumen::activo).containsExactly(false, true);
	}
}
