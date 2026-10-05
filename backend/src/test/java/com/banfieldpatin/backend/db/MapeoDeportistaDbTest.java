package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.banfieldpatin.backend.compartido.error.RestriccionViolada;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.deportistas.Deportista;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.deportistas.dto.DeportistaDetalle;
import com.banfieldpatin.backend.deportistas.dto.DeportistaSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.persistence.EntityManagerFactory;

/**
 * Deportistas contra PostgreSQL real (beans y proxies transaccionales reales, Flyway V1-V4, ddl-auto=validate): mapeo de
 * todos los campos permanentes, normalizacion de DNI/CUIL, estado, restricciones unicas reales por escuela (con el
 * NOMBRE de restriccion que usa el servicio para el 409), inmutabilidad de la escuela, limites de columna, que
 * activar/desactivar no toque vinculos y sentencias de Hibernate.
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class MapeoDeportistaDbTest extends BaseDbTest {

	private static final DatosSolicitud DATOS = new DatosSolicitud("10.0.0.9", "JUnit");
	private static final String DNI_CRUDO = "31.222.333";
	private static final String DNI = "31222333";
	// CUIL con digito verificador valido para el prefijo 20 y el DNI 31222333 (suma 84 -> r = 11 - 7 = 4).
	private static final String CUIL = "20312223334";
	private static final String CUIL_CRUDO = "20-31222333-4";

	@Autowired
	JdbcClient jdbc;
	@Autowired
	DeportistaAdminService servicio;
	@Autowired
	DeportistaRepository deportistas;
	@Autowired
	EntityManagerFactory emf;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UUID adminId;
	private UsuarioAutenticado admin;
	private UsuarioAutenticado adminDeB;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("dep-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("dep-b-" + UUID.randomUUID());
		adminId = datos.admin(escuelaA, "admin@a.example", true);
		admin = new UsuarioAutenticado(adminId, escuelaA, null, Rol.ADMIN);
		adminDeB = new UsuarioAutenticado(datos.admin(escuelaB, "admin@b.example", true), escuelaB, null, Rol.ADMIN);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private DeportistaSolicitud completa(String dni, String cuil) {
		return new DeportistaSolicitud(dni, "Juan", "Perez", cuil, LocalDate.of(2012, 5, 10), "Argentina", "Calle 1 N 100",
				"Piso 2 Dto B", "Banfield", "Lomas de Zamora", "1828", "11-5555-0000", "juan@example.com");
	}

	private DeportistaSolicitud minima(String dni) {
		return new DeportistaSolicitud(dni, "Juan", "Perez", null, null, null, null, null, null, null, null, null, null);
	}

	private Map<String, Object> fila(UUID id) {
		return jdbc.sql("SELECT * FROM gestion_patin.deportista WHERE id = :id").param("id", id).query().singleRow();
	}

	private long contarDeportistas(UUID escuela) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.deportista WHERE escuela_id = :e").param("e", escuela)
				.query(Long.class).single();
	}

	private long contarAuditorias(UUID escuela) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e").param("e", escuela)
				.query(Long.class).single();
	}

	private long sentencias(Runnable accion) {
		var estadisticas = emf.unwrap(SessionFactory.class).getStatistics();
		estadisticas.clear();
		accion.run();
		return estadisticas.getPrepareStatementCount();
	}

	// ---------- mapeo ----------

	@Test
	void crearPersisteTodosLosCamposPermanentesNormalizaDocumentosYDejaElDeportistaActivo() {
		DeportistaDetalle creado = servicio.crear(admin, completa(DNI_CRUDO, CUIL_CRUDO), DATOS);

		Map<String, Object> fila = fila(creado.id());
		assertThat(fila.get("escuela_id")).isEqualTo(escuelaA);
		assertThat(fila.get("nombre")).isEqualTo("Juan");
		assertThat(fila.get("apellido")).isEqualTo("Perez");
		assertThat(fila.get("dni")).isEqualTo(DNI);
		assertThat(fila.get("cuil")).isEqualTo(CUIL);
		assertThat(fila.get("fecha_nacimiento")).hasToString("2012-05-10");
		assertThat(fila.get("nacionalidad")).isEqualTo("Argentina");
		assertThat(fila.get("domicilio")).isEqualTo("Calle 1 N 100");
		assertThat(fila.get("otros_datos_domicilio")).isEqualTo("Piso 2 Dto B");
		assertThat(fila.get("localidad")).isEqualTo("Banfield");
		assertThat(fila.get("partido")).isEqualTo("Lomas de Zamora");
		assertThat(fila.get("codigo_postal")).isEqualTo("1828");
		assertThat(fila.get("telefono_contacto")).isEqualTo("11-5555-0000");
		assertThat(fila.get("email_federativo")).isEqualTo("juan@example.com");
		assertThat(fila.get("activo")).isEqualTo(true);
		assertThat(creado.dni()).isEqualTo(DNI);
		assertThat(creado.cuil()).isEqualTo(CUIL);

		assertThat(servicio.obtener(admin, creado.id())).isEqualTo(creado);
	}

	@Test
	void conSoloDniNombreYApellidoLosOpcionalesQuedanNulosYElEstadoActivo() {
		DeportistaDetalle creado = servicio.crear(admin, minima(DNI), DATOS);

		Map<String, Object> fila = fila(creado.id());
		for (String columna : new String[] { "cuil", "fecha_nacimiento", "nacionalidad", "domicilio",
				"otros_datos_domicilio", "localidad", "partido", "codigo_postal", "telefono_contacto",
				"email_federativo" }) {
			assertThat(fila.get(columna)).as(columna).isNull();
		}
		assertThat(fila.get("activo")).isEqualTo(true);
	}

	@Test
	void editarReemplazaTodosLosCamposYVaciarUnOpcionalLoDejaNulo() {
		UUID id = servicio.crear(admin, completa(DNI, CUIL), DATOS).id();

		DeportistaDetalle editado = servicio.actualizar(admin, id, minima(DNI), DATOS);

		Map<String, Object> fila = fila(id);
		assertThat(fila.get("cuil")).isNull();
		assertThat(fila.get("localidad")).isNull();
		assertThat(fila.get("email_federativo")).isNull();
		assertThat(fila.get("dni")).isEqualTo(DNI);
		assertThat(editado.cuil()).isNull();
		assertThat(editado.activo()).isTrue();
	}

	@Test
	void unaColumnaQueSuperaSuLongitudNoLlegaALaBaseSinEscribirNiAuditar() {
		// El DTO lo rechaza antes (400); aqui se comprueba el limite real de la columna (varchar(100)).
		assertThatThrownBy(() -> servicio.crear(admin, new DeportistaSolicitud(DNI, "n".repeat(101), "Perez", null, null,
				null, null, null, null, null, null, null, null), DATOS)).isInstanceOf(DataIntegrityViolationException.class);

		assertThat(contarDeportistas(escuelaA)).isZero();
		assertThat(contarAuditorias(escuelaA)).isZero();
	}

	@Test
	void laEscuelaEsInmutableAunSiSeIntentaCambiarEnLaEntidad() {
		UUID id = servicio.crear(admin, minima(DNI), DATOS).id();

		Deportista d = deportistas.findByIdAndEscuelaId(id, escuelaA).orElseThrow();
		ReflectionTestUtils.setField(d, "escuelaId", escuelaB);
		ReflectionTestUtils.setField(d, "localidad", "Cambiada");
		deportistas.saveAndFlush(d);

		assertThat(fila(id).get("escuela_id")).isEqualTo(escuelaA);
		assertThat(fila(id).get("localidad")).isEqualTo("Cambiada");
	}

	@Test
	void elEstadoActivoSePersisteEnAmbosSentidos() {
		UUID id = servicio.crear(admin, minima(DNI), DATOS).id();

		assertThat(servicio.desactivar(admin, id, DATOS).activo()).isFalse();
		assertThat(fila(id).get("activo")).isEqualTo(false);
		assertThat(servicio.activar(admin, id, DATOS).activo()).isTrue();
		assertThat(fila(id).get("activo")).isEqualTo(true);
	}

	// ---------- restricciones unicas reales ----------

	@Test
	void elIndiceUnicoDeDniDaElNombreDeRestriccionQueUsaElServicio() {
		datos.deportista(escuelaA, DNI, "Existente", "Perez");

		assertThatThrownBy(() -> deportistas.saveAndFlush(Deportista.crear(escuelaA, "Otro", "Gomez", DNI, null, null,
				null, null, null, null, null, null, null, null)))
				.isInstanceOfSatisfying(DataIntegrityViolationException.class,
						e -> assertThat(RestriccionViolada.nombre(e)).contains("uq_deportista_dni_escuela"));
	}

	@Test
	void elIndiceUnicoDeDniNoEsParcialUnInactivoSigueReservandoSuDni() {
		datos.deportista(escuelaA, DNI, "Existente", "Perez", false);

		assertThatThrownBy(() -> deportistas.saveAndFlush(Deportista.crear(escuelaA, "Otro", "Gomez", DNI, null, null,
				null, null, null, null, null, null, null, null)))
				.isInstanceOfSatisfying(DataIntegrityViolationException.class,
						e -> assertThat(RestriccionViolada.nombre(e)).contains("uq_deportista_dni_escuela"));
	}

	@Test
	void elIndiceUnicoDeCuilDaSuNombreYVariosCuilNulosConviven() {
		datos.deportistaConCuil(escuelaA, "30000001", CUIL, "Existente", "Perez");

		assertThatThrownBy(() -> deportistas.saveAndFlush(Deportista.crear(escuelaA, "Otro", "Gomez", "30000002", CUIL,
				null, null, null, null, null, null, null, null, null)))
				.isInstanceOfSatisfying(DataIntegrityViolationException.class,
						e -> assertThat(RestriccionViolada.nombre(e)).contains("uq_deportista_cuil_escuela"));

		deportistas.saveAndFlush(Deportista.crear(escuelaA, "Uno", "Gomez", "30000003", null, null, null, null, null, null,
				null, null, null, null));
		deportistas.saveAndFlush(Deportista.crear(escuelaA, "Dos", "Gomez", "30000004", null, null, null, null, null, null,
				null, null, null, null));
	}

	@Test
	void elMismoDniYElMismoCuilEnOtraEscuelaSonValidos() {
		servicio.crear(admin, completa(DNI, CUIL), DATOS);

		DeportistaDetalle deB = servicio.crear(adminDeB, completa(DNI_CRUDO, CUIL_CRUDO), DATOS);

		assertThat(deB.dni()).isEqualTo(DNI);
		assertThat(fila(deB.id()).get("escuela_id")).isEqualTo(escuelaB);
		assertThat(contarDeportistas(escuelaA)).isEqualTo(1);
		assertThat(contarDeportistas(escuelaB)).isEqualTo(1);
	}

	// ---------- sin cascada (F4) ----------

	@Test
	void activarYDesactivarNoTocanLosVinculosNiLasFamilias() {
		UUID familia = datos.familia(escuelaA, "Perez", true);
		UUID id = servicio.crear(admin, minima(DNI), DATOS).id();
		UUID vinculo = datos.vinculo(escuelaA, familia, id, "ACTIVO", true, adminId);
		Map<String, Object> antes = jdbc.sql("SELECT * FROM gestion_patin.familia_deportista WHERE id = :id")
				.param("id", vinculo).query().singleRow();

		servicio.desactivar(admin, id, DATOS);
		servicio.desactivar(admin, id, DATOS);
		servicio.activar(admin, id, DATOS);
		servicio.desactivar(admin, id, DATOS);
		servicio.actualizar(admin, id, completa(DNI, null), DATOS);

		Map<String, Object> despues = jdbc.sql("SELECT * FROM gestion_patin.familia_deportista WHERE id = :id")
				.param("id", vinculo).query().singleRow();
		assertThat(despues).isEqualTo(antes);
		assertThat(despues.get("estado")).isEqualTo("ACTIVO");
		assertThat(despues.get("es_principal")).isEqualTo(true);
		assertThat(jdbc.sql("SELECT activa FROM gestion_patin.familia WHERE id = :id").param("id", familia)
				.query(Boolean.class).single()).isTrue();
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.familia_deportista WHERE escuela_id = :e")
				.param("e", escuelaA).query(Long.class).single()).isEqualTo(1);
	}

	// ---------- aislamiento por escuela ----------

	@Test
	void unAdminDeOtraEscuelaNoVeNiCambiaNiActivaDeportistas() {
		UUID id = servicio.crear(admin, completa(DNI, CUIL), DATOS).id();
		long auditoriasAntes = contarAuditorias(escuelaA);
		Map<String, Object> filaAntes = fila(id);

		for (Runnable accion : new Runnable[] { () -> servicio.obtener(adminDeB, id),
				() -> servicio.actualizar(adminDeB, id, minima("30999888"), DATOS),
				() -> servicio.activar(adminDeB, id, DATOS), () -> servicio.desactivar(adminDeB, id, DATOS),
				() -> servicio.obtener(adminDeB, UUID.randomUUID()) }) {
			assertThatThrownBy(accion::run).hasMessage("El deportista no existe.");
		}

		assertThat(fila(id)).isEqualTo(filaAntes);
		assertThat(contarAuditorias(escuelaA)).isEqualTo(auditoriasAntes);
		assertThat(contarAuditorias(escuelaB)).isZero();
		assertThat(contarDeportistas(escuelaB)).isZero();
	}

	// ---------- sentencias de Hibernate ----------

	@Test
	void obtenerUsaUnaSolaSentenciaYElAltaYLaEdicionPocas() {
		UUID id = servicio.crear(admin, completa(DNI, CUIL), DATOS).id();
		for (int i = 0; i < 6; i++) {
			datos.deportista(escuelaA, "4000000" + i, "Nombre" + i, "Apellido" + i);
		}

		long obtener = sentencias(() -> servicio.obtener(admin, id));
		long crear = sentencias(() -> servicio.crear(admin, minima("31999888"), DATOS));
		long editar = sentencias(() -> servicio.actualizar(admin, id, completa("31.999.777", CUIL), DATOS));

		assertThat(obtener).isLessThanOrEqualTo(1);
		// pre-chequeo de DNI + insert (+ la auditoria por JDBC); deportista + pre-chequeo de DNI + update (+ auditoria).
		assertThat(crear).isLessThanOrEqualTo(3);
		assertThat(editar).isLessThanOrEqualTo(3);
	}
}
