package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.error.RestriccionViolada;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.tutores.Tutor;
import com.banfieldpatin.backend.familias.tutores.TutorAdminService;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
import com.banfieldpatin.backend.familias.tutores.dto.TutorRespuesta;
import com.banfieldpatin.backend.familias.tutores.dto.TutorSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.persistence.EntityManagerFactory;

/**
 * Tutores contra PostgreSQL real (beans y proxies transaccionales reales, Flyway V1-V4, ddl-auto=validate): mapeo,
 * inmutabilidad de la familia, FK compuesta entre escuelas, indice de V4, tutores sin unicidad de DNI, familia inactiva
 * (409 sin escribir ni auditar), auditoria sin datos personales y aislamiento por escuela.
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class MapeoTutorDbTest extends BaseDbTest {

	private static final DatosSolicitud DATOS = new DatosSolicitud("10.0.0.9", "JUnit");
	// Cadenas propias de esta prueba: la auditoria se comprueba contra ellas (nunca contra "7 digitos seguidos").
	private static final String DNI_CRUDO = "31.222.333";
	private static final String DNI_NORMALIZADO = "31222333";
	private static final String TELEFONO = "11-4444-9999";
	private static final String EMAIL = "tutora.secreta@example.com";

	@Autowired
	JdbcClient jdbc;
	@Autowired
	TutorAdminService servicio;
	@Autowired
	TutorRepository tutores;
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
		escuelaA = datos.escuela("tut-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("tut-b-" + UUID.randomUUID());
		adminId = datos.admin(escuelaA, "admin@a.example", true);
		admin = new UsuarioAutenticado(adminId, escuelaA, null, Rol.ADMIN);
		adminDeB = new UsuarioAutenticado(datos.admin(escuelaB, "admin@b.example", true), escuelaB, null, Rol.ADMIN);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private TutorSolicitud solicitud(String dni, String telefono) {
		return new TutorSolicitud("Ana", "Perez", dni, telefono, EMAIL, "Madre");
	}

	private Map<String, Object> fila(UUID id) {
		return jdbc.sql("""
				SELECT escuela_id, familia_id, usuario_id, nombre, apellido, dni, telefono, email, parentesco, activo
				FROM gestion_patin.tutor WHERE id = :id
				""").param("id", id).query().singleRow();
	}

	private long contarTutores(UUID escuela) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.tutor WHERE escuela_id = :e").param("e", escuela)
				.query(Long.class).single();
	}

	private long auditoriasTotales(UUID escuela) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e").param("e", escuela)
				.query(Long.class).single();
	}

	private long sentencias(Runnable accion) {
		var estadisticas = emf.unwrap(SessionFactory.class).getStatistics();
		estadisticas.clear();
		accion.run();
		return estadisticas.getPrepareStatementCount();
	}

	private static void assertError(Throwable e, String codigo) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, ex -> assertThat(ex.getCodigo()).isEqualTo(codigo));
	}

	// ---------- mapeo ----------

	@Test
	void crearPersisteTodosLosCamposActivoYSinUsuarioEnLaEscuelaDelToken() {
		UUID familia = datos.familia(escuelaA, "Perez", true);

		TutorRespuesta creado = servicio.crear(admin, familia, solicitud(DNI_CRUDO, TELEFONO), DATOS);

		Map<String, Object> fila = fila(creado.id());
		assertThat(fila.get("escuela_id")).isEqualTo(escuelaA);
		assertThat(fila.get("familia_id")).isEqualTo(familia);
		assertThat(fila.get("usuario_id")).isNull();
		assertThat(fila.get("nombre")).isEqualTo("Ana");
		assertThat(fila.get("apellido")).isEqualTo("Perez");
		assertThat(fila.get("dni")).isEqualTo(DNI_NORMALIZADO);
		assertThat(fila.get("telefono")).isEqualTo(TELEFONO);
		assertThat(fila.get("email")).isEqualTo(EMAIL);
		assertThat(fila.get("parentesco")).isEqualTo("Madre");
		assertThat(fila.get("activo")).isEqualTo(true);
		assertThat(creado.familiaId()).isEqualTo(familia);

		TutorRespuesta leido = servicio.obtener(admin, creado.id());
		assertThat(leido).isEqualTo(creado);
	}

	@Test
	void unTutorSoloConNombreYApellidoGuardaLosOpcionalesComoNulos() {
		UUID familia = datos.familia(escuelaA, "Perez", true);

		TutorRespuesta creado = servicio.crear(admin, familia,
				new TutorSolicitud("Ana", "Perez", null, null, null, null), DATOS);

		Map<String, Object> fila = fila(creado.id());
		assertThat(fila.get("dni")).isNull();
		assertThat(fila.get("telefono")).isNull();
		assertThat(fila.get("email")).isNull();
		assertThat(fila.get("parentesco")).isNull();
	}

	@Test
	void dosTutoresPuedenCompartirElMismoDniEnLaFamiliaYEntreFamilias() {
		UUID familiaUno = datos.familia(escuelaA, "Uno", true);
		UUID familiaDos = datos.familia(escuelaA, "Dos", true);

		TutorRespuesta a = servicio.crear(admin, familiaUno, solicitud(DNI_CRUDO, null), DATOS);
		TutorRespuesta b = servicio.crear(admin, familiaUno, solicitud(DNI_CRUDO, null), DATOS);
		TutorRespuesta c = servicio.crear(admin, familiaDos, solicitud(DNI_NORMALIZADO, null), DATOS);

		assertThat(List.of(a.id(), b.id(), c.id())).doesNotHaveDuplicates();
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.tutor WHERE escuela_id = :e AND dni = :d")
				.param("e", escuelaA).param("d", DNI_NORMALIZADO).query(Long.class).single()).isEqualTo(3);
	}

	@Test
	void unaColumnaDeMasDeLaLongitudNoLlegaALaBaseSinEscribirNiAuditar() {
		UUID familia = datos.familia(escuelaA, "Perez", true);

		// El DTO lo rechaza antes (400); aqui se comprueba el limite real de la columna (varchar(40)).
		assertThatThrownBy(() -> servicio.crear(admin, familia,
				new TutorSolicitud("Ana", "Perez", null, "1".repeat(41), null, null), DATOS))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(contarTutores(escuelaA)).isZero();
		assertThat(auditoriasTotales(escuelaA)).isZero();
	}

	// ---------- inmutabilidad de la familia ----------

	@Test
	void laFamiliaEsInmutableAunSiSeIntentaCambiarEnLaEntidad() {
		UUID original = datos.familia(escuelaA, "Original", true);
		UUID otra = datos.familia(escuelaA, "Otra", true);
		UUID id = servicio.crear(admin, original, solicitud(null, TELEFONO), DATOS).id();

		Tutor tutor = tutores.findByIdAndEscuelaId(id, escuelaA).orElseThrow();
		ReflectionTestUtils.setField(tutor, "familiaId", otra);
		ReflectionTestUtils.setField(tutor, "telefono", "11-0000-1111");
		tutores.saveAndFlush(tutor);

		Map<String, Object> fila = fila(id);
		assertThat(fila.get("familia_id")).isEqualTo(original);
		assertThat(fila.get("telefono")).isEqualTo("11-0000-1111");
	}

	@Test
	void elEstadoActivoTampocoCambiaPorLaEntidad() {
		UUID familia = datos.familia(escuelaA, "Perez", true);
		UUID id = servicio.crear(admin, familia, solicitud(null, null), DATOS).id();

		Tutor tutor = tutores.findByIdAndEscuelaId(id, escuelaA).orElseThrow();
		ReflectionTestUtils.setField(tutor, "activo", false);
		ReflectionTestUtils.setField(tutor, "nombre", "Otro");
		tutores.saveAndFlush(tutor);

		assertThat(fila(id).get("activo")).isEqualTo(true);
		assertThat(fila(id).get("nombre")).isEqualTo("Otro");
	}

	@Test
	void unaEdicionPorElServicioNuncaMueveElTutorDeFamilia() {
		UUID original = datos.familia(escuelaA, "Original", true);
		datos.familia(escuelaA, "Otra", true);
		UUID id = servicio.crear(admin, original, solicitud(null, null), DATOS).id();

		TutorRespuesta editado = servicio.actualizar(admin, id, solicitud(null, TELEFONO), DATOS);

		assertThat(editado.familiaId()).isEqualTo(original);
		assertThat(fila(id).get("familia_id")).isEqualTo(original);
	}

	// ---------- indice y FK de V4/V1 ----------

	@Test
	void elIndiceIxTutorFamiliaExisteSobreEscuelaYFamilia() {
		String definicion = jdbc.sql("""
				SELECT indexdef FROM pg_indexes
				WHERE schemaname = 'gestion_patin' AND tablename = 'tutor' AND indexname = 'ix_tutor_familia'
				""").query(String.class).single();

		assertThat(definicion).contains("(escuela_id, familia_id)");
	}

	@Test
	void deFamiliaDevuelveSoloLosDeLaFamiliaOrdenadosYSinLosDeOtraEscuela() {
		UUID familia = datos.familia(escuelaA, "Perez", true);
		UUID otraFamilia = datos.familia(escuelaA, "Otra", true);
		UUID ajena = datos.familia(escuelaB, "Ajena", true);
		datos.tutor(escuelaA, familia, "Zoe", "Gomez");
		datos.tutor(escuelaA, familia, "Ana", "gomez");
		datos.tutor(escuelaA, familia, "Beto", "Alvarez");
		datos.tutor(escuelaA, otraFamilia, "Otro", "Otro");
		datos.tutor(escuelaB, ajena, "Ajeno", "Ajeno");

		assertThat(tutores.deFamilia(escuelaA, familia)).extracting(Tutor::getNombre)
				.containsExactly("Beto", "Ana", "Zoe");
		assertThat(tutores.deFamilia(escuelaA, ajena)).isEmpty();
		assertThat(tutores.deFamilia(escuelaB, familia)).isEmpty();
		assertThat(tutores.activosDeFamilia(escuelaA, familia)).hasSize(3);
	}

	@Test
	void activosDeFamiliaExcluyeLosInactivosPeroDeFamiliaLosIncluye() {
		UUID familia = datos.familia(escuelaA, "Perez", true);
		UUID activo = datos.tutor(escuelaA, familia, "Ana", "Perez");
		UUID inactivo = datos.tutor(escuelaA, familia, "Luis", "Perez");
		jdbc.sql("UPDATE gestion_patin.tutor SET activo = false WHERE id = :id").param("id", inactivo).update();

		assertThat(tutores.activosDeFamilia(escuelaA, familia)).extracting(Tutor::getId).containsExactly(activo);
		assertThat(tutores.deFamilia(escuelaA, familia)).extracting(Tutor::getId).containsExactlyInAnyOrder(activo,
				inactivo);
	}

	@Test
	void laClaveForaneaCompuestaImpideUnTutorDeUnaEscuelaEnUnaFamiliaDeOtra() {
		UUID familiaDeB = datos.familia(escuelaB, "Ajena", true);

		assertThatThrownBy(() -> tutores.saveAndFlush(
				Tutor.crear(escuelaA, familiaDeB, "Ana", "Perez", null, null, null, null)))
				.isInstanceOfSatisfying(DataIntegrityViolationException.class, e -> assertThat(
						RestriccionViolada.nombre(e)).contains("fk_tutor_familia_misma_escuela"));

		assertThat(contarTutores(escuelaA)).isZero();
	}

	// ---------- aislamiento por escuela ----------

	@Test
	void unAdminDeOtraEscuelaNoVeNiCambiaNiCreaTutores() {
		UUID familiaA = datos.familia(escuelaA, "Perez", true);
		UUID tutorA = servicio.crear(admin, familiaA, solicitud(null, TELEFONO), DATOS).id();
		long auditoriasAntes = auditoriasTotales(escuelaA);

		assertThatThrownBy(() -> servicio.obtener(adminDeB, tutorA)).satisfies(e -> assertError(e, "TUTOR_NO_ENCONTRADO"));
		assertThatThrownBy(() -> servicio.actualizar(adminDeB, tutorA, solicitud(null, "11-9999-9999"), DATOS))
				.satisfies(e -> assertError(e, "TUTOR_NO_ENCONTRADO"));
		assertThatThrownBy(() -> servicio.crear(adminDeB, familiaA, solicitud(null, null), DATOS))
				.satisfies(e -> assertError(e, "FAMILIA_NO_ENCONTRADA"));
		assertThatThrownBy(() -> servicio.obtener(adminDeB, UUID.randomUUID()))
				.satisfies(e -> assertError(e, "TUTOR_NO_ENCONTRADO"));

		assertThat(fila(tutorA).get("telefono")).isEqualTo(TELEFONO);
		assertThat(contarTutores(escuelaA)).isEqualTo(1);
		assertThat(contarTutores(escuelaB)).isZero();
		assertThat(auditoriasTotales(escuelaA)).isEqualTo(auditoriasAntes);
		assertThat(auditoriasTotales(escuelaB)).isZero();
		assertThat(servicio.obtener(admin, tutorA).familiaId()).isEqualTo(familiaA);
	}

	// ---------- auditoria ----------

	@Test
	void laAuditoriaGuardaIdsYNombresDeCamposPeroNingunDatoPersonal() {
		UUID familia = datos.familia(escuelaA, "Perez", true);

		UUID id = servicio.crear(admin, familia, solicitud(DNI_CRUDO, TELEFONO), DATOS).id();
		servicio.actualizar(admin, id, solicitud(null, "11-7777-8888"), DATOS);
		servicio.actualizar(admin, id, solicitud(null, "11-7777-8888"), DATOS); // identica: sin auditoria

		List<Map<String, Object>> eventos = jdbc.sql("""
				SELECT accion, usuario_id, recurso_tipo, recurso_id, detalle::text AS detalle
				FROM gestion_patin.auditoria WHERE escuela_id = :e ORDER BY id
				""").param("e", escuelaA).query().listOfRows();
		assertThat(eventos).hasSize(2);
		assertThat(eventos.get(0).get("accion")).isEqualTo("TUTOR_CREADO");
		assertThat(eventos.get(1).get("accion")).isEqualTo("TUTOR_ACTUALIZADO");
		for (Map<String, Object> evento : eventos) {
			assertThat(evento.get("usuario_id")).isEqualTo(adminId);
			assertThat(evento.get("recurso_tipo")).isEqualTo("TUTOR");
			assertThat(evento.get("recurso_id")).isEqualTo(id);
			String detalle = (String) evento.get("detalle");
			assertThat(detalle).contains(familia.toString());
			assertThat(detalle).doesNotContain(DNI_CRUDO).doesNotContain(DNI_NORMALIZADO).doesNotContain(TELEFONO)
					.doesNotContain("11-7777-8888").doesNotContain(EMAIL).doesNotContain("Ana").doesNotContain("Perez");
		}
		assertThat((String) eventos.get(1).get("detalle")).contains("camposModificados").contains("dni")
				.contains("telefono");
	}

	@Test
	void editarElMismoTutorSinCambiosNoAuditaNiEscribe() {
		UUID familia = datos.familia(escuelaA, "Perez", true);
		UUID id = servicio.crear(admin, familia, solicitud(DNI_CRUDO, TELEFONO), DATOS).id();
		long antes = auditoriasTotales(escuelaA);

		servicio.actualizar(admin, id, solicitud(DNI_NORMALIZADO, TELEFONO), DATOS);

		assertThat(auditoriasTotales(escuelaA)).isEqualTo(antes);
	}

	// ---------- familia inactiva (N3) ----------

	@Test
	void conLaFamiliaInactivaAltaYEdicionDan409SinEscribirNiAuditarYLaLecturaSigueFuncionando() {
		UUID familia = datos.familia(escuelaA, "Perez", true);
		UUID id = servicio.crear(admin, familia, solicitud(DNI_CRUDO, TELEFONO), DATOS).id();
		long tutoresAntes = contarTutores(escuelaA);
		long auditoriasAntes = auditoriasTotales(escuelaA);
		Map<String, Object> filaAntes = fila(id);

		datos.desactivarFamilia(familia);

		assertThatThrownBy(() -> servicio.crear(admin, familia, solicitud(null, null), DATOS))
				.satisfies(e -> assertError(e, "FAMILIA_INACTIVA"));
		assertThatThrownBy(() -> servicio.actualizar(admin, id, solicitud(DNI_CRUDO, "11-1111-2222"), DATOS))
				.satisfies(e -> assertError(e, "FAMILIA_INACTIVA"));
		// Una edicion IDENTICA tambien se rechaza: la familia se comprueba antes de detectar "sin cambios".
		assertThatThrownBy(() -> servicio.actualizar(admin, id, solicitud(DNI_CRUDO, TELEFONO), DATOS))
				.satisfies(e -> assertError(e, "FAMILIA_INACTIVA"));

		assertThat(contarTutores(escuelaA)).isEqualTo(tutoresAntes);
		assertThat(auditoriasTotales(escuelaA)).isEqualTo(auditoriasAntes);
		assertThat(fila(id)).isEqualTo(filaAntes);
		// Lectura permitida sobre una familia inactiva.
		assertThat(servicio.obtener(admin, id).familiaId()).isEqualTo(familia);

		datos.reactivarFamilia(familia);

		assertThat(servicio.actualizar(admin, id, solicitud(DNI_CRUDO, "11-1111-2222"), DATOS).telefono())
				.isEqualTo("11-1111-2222");
		servicio.crear(admin, familia, solicitud(null, null), DATOS);
		assertThat(contarTutores(escuelaA)).isEqualTo(tutoresAntes + 1);
		assertThat(auditoriasTotales(escuelaA)).isEqualTo(auditoriasAntes + 2);
	}

	// ---------- sentencias de Hibernate ----------

	@Test
	void crearUsaComoMaximoDosSentenciasDeHibernateYEditarTres() {
		UUID familia = datos.familia(escuelaA, "Perez", true);

		long crear = sentencias(() -> servicio.crear(admin, familia, solicitud(null, null), DATOS));
		UUID id = jdbc.sql("SELECT id FROM gestion_patin.tutor WHERE escuela_id = :e").param("e", escuelaA)
				.query(UUID.class).single();
		long editar = sentencias(() -> servicio.actualizar(admin, id, solicitud(null, TELEFONO), DATOS));

		// familia + insert (+ la auditoria por JDBC = 3 en total); tutor + familia + update (+ auditoria = 4).
		assertThat(crear).isLessThanOrEqualTo(2);
		assertThat(editar).isLessThanOrEqualTo(3);
	}
}
