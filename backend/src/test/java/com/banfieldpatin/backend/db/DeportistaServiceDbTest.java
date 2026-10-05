package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.deportistas.dto.DeportistaSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Servicio de deportistas contra PostgreSQL real: los codigos de DNI segun el titular este activo o inactivo contra las
 * restricciones REALES, la traduccion por nombre de restriccion cuando la base arbitra una carrera (bloqueando de verdad
 * una alta detras de otra sin confirmar y con dos hilos libres), el mismo DNI en otra escuela, y una auditoria y unos
 * logs que nunca contienen DNI ni CUIL (las aserciones se hacen contra las cadenas propias de la prueba).
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class DeportistaServiceDbTest extends BaseDbTest {

	private static final DatosSolicitud DATOS = new DatosSolicitud("10.0.0.9", "JUnit");
	// Cadenas propias de esta prueba (no un patron generico de digitos: los UUID pueden ser todo digitos).
	private static final String DNI_CRUDO = "36.789.012";
	private static final String DNI = "36789012";
	private static final String CUIL = "20367890127";
	private static final String CUIL_CRUDO = "20-36789012-7";

	@Autowired
	JdbcClient jdbc;
	@Autowired
	DeportistaAdminService servicio;
	@Autowired
	TransactionTemplate transaccion;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UUID adminId;
	private UsuarioAutenticado admin;
	private UsuarioAutenticado adminDeB;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("dsvc-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("dsvc-b-" + UUID.randomUUID());
		adminId = datos.admin(escuelaA, "admin@a.example", true);
		admin = new UsuarioAutenticado(adminId, escuelaA, null, Rol.ADMIN);
		adminDeB = new UsuarioAutenticado(datos.admin(escuelaB, "admin@b.example", true), escuelaB, null, Rol.ADMIN);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private DeportistaSolicitud solicitud(String dni, String cuil) {
		return new DeportistaSolicitud(dni, "Zuleica", "Quintana", cuil, null, null, "Calle Secreta 123", null, null, null,
				null, "11-4444-5555", "zuleica@example.com");
	}

	private long contar(String tabla, UUID escuela) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin." + tabla + " WHERE escuela_id = :e").param("e", escuela)
				.query(Long.class).single();
	}

	private static void assertError(Throwable e, String codigo) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, ex -> assertThat(ex.getCodigo()).isEqualTo(codigo));
	}

	// ---------- codigos de DNI contra las restricciones reales ----------

	@Test
	void unDniDeUnDeportistaActivoDa409DniDuplicadoYElDeUnInactivoDniReservadoPorInactivo() {
		datos.deportista(escuelaA, DNI, "Activo", "Titular");
		datos.deportista(escuelaA, "36789013", "Inactivo", "Titular", false);

		assertThatThrownBy(() -> servicio.crear(admin, solicitud(DNI_CRUDO, null), DATOS))
				.satisfies(e -> assertError(e, "DNI_DUPLICADO")).hasMessage("Ya existe un deportista con ese DNI.");
		assertThatThrownBy(() -> servicio.crear(admin, solicitud("36.789.013", null), DATOS))
				.satisfies(e -> assertError(e, "DNI_RESERVADO_POR_INACTIVO"))
				.hasMessage("Ya existe un deportista inactivo con ese DNI. Reactivalo en lugar de crear uno nuevo.");

		assertThat(contar("deportista", escuelaA)).isEqualTo(2);
		assertThat(contar("auditoria", escuelaA)).isZero();
	}

	@Test
	void reactivarAlInactivoPermiteSeguirYElDniVuelveAReservarseComoDuplicado() {
		UUID inactivo = datos.deportista(escuelaA, DNI, "Inactivo", "Titular", false);

		servicio.activar(admin, inactivo, DATOS);

		assertThatThrownBy(() -> servicio.crear(admin, solicitud(DNI, null), DATOS))
				.satisfies(e -> assertError(e, "DNI_DUPLICADO"));
	}

	@Test
	void unCuilYaUsadoEnLaEscuelaDa409CuilDuplicado() {
		datos.deportistaConCuil(escuelaA, "30000001", CUIL, "Titular", "Cuil");

		assertThatThrownBy(() -> servicio.crear(admin, solicitud(DNI, CUIL_CRUDO), DATOS))
				.satisfies(e -> assertError(e, "CUIL_DUPLICADO"));

		assertThat(contar("deportista", escuelaA)).isEqualTo(1);
		assertThat(contar("auditoria", escuelaA)).isZero();
	}

	@Test
	void laEdicionQueConservaSuDniYCuilNoEsConflictoPeroCambiarlosAlDeOtroSi() {
		UUID propio = servicio.crear(admin, solicitud(DNI, CUIL), DATOS).id();
		datos.deportista(escuelaA, "36789014", "Otro", "Activo");
		datos.deportista(escuelaA, "36789015", "Otro", "Inactivo", false);
		datos.deportistaConCuil(escuelaA, "36789016", "20123456786", "Otro", "Cuil");

		// Conserva el propio DNI y CUIL y cambia otro dato: 200.
		assertThat(servicio.actualizar(admin, propio, new DeportistaSolicitud(DNI_CRUDO, "Zuleica", "Quintana",
				CUIL_CRUDO, null, null, "Otra calle", null, null, null, null, null, null), DATOS).domicilio())
				.isEqualTo("Otra calle");

		assertThatThrownBy(() -> servicio.actualizar(admin, propio, solicitud("36789014", CUIL), DATOS))
				.satisfies(e -> assertError(e, "DNI_DUPLICADO"));
		assertThatThrownBy(() -> servicio.actualizar(admin, propio, solicitud("36789015", CUIL), DATOS))
				.satisfies(e -> assertError(e, "DNI_RESERVADO_POR_INACTIVO"));
		assertThatThrownBy(() -> servicio.actualizar(admin, propio, solicitud(DNI, "20-12345678-6"), DATOS))
				.satisfies(e -> assertError(e, "CUIL_DUPLICADO"));

		// Cambiar a un DNI libre si funciona.
		assertThat(servicio.actualizar(admin, propio, solicitud("36789099", CUIL), DATOS).dni()).isEqualTo("36789099");
	}

	@Test
	void elMismoDniYElMismoCuilEnOtraEscuelaSePermiten() {
		servicio.crear(admin, solicitud(DNI, CUIL), DATOS);

		assertThat(servicio.crear(adminDeB, solicitud(DNI_CRUDO, CUIL_CRUDO), DATOS).dni()).isEqualTo(DNI);
		assertThat(contar("deportista", escuelaA)).isEqualTo(1);
		assertThat(contar("deportista", escuelaB)).isEqualTo(1);
	}

	// ---------- carreras ----------

	/**
	 * Determinista: A inserta su alta SIN confirmar; B hace su pre-chequeo (no ve la fila de A) y se queda bloqueada
	 * en el INSERT por el indice unico; solo cuando B esta esperando A confirma. Entonces la base rechaza a B y el
	 * servicio traduce la restriccion real a 409 DNI_DUPLICADO (nunca un 500).
	 */
	@Test
	void unaAltaBloqueadaDetrasDeOtraSinConfirmarPierdeConDniDuplicadoPorElNombreDeLaRestriccion() throws Exception {
		ListAppender<ILoggingEvent> logs = capturarLogs();
		ExecutorService hilos = Executors.newFixedThreadPool(2);
		CountDownLatch aInserto = new CountDownLatch(1);
		CountDownLatch bEsperando = new CountDownLatch(1);
		try {
			Future<Object> alta = hilos.submit(() -> transaccion.execute(estado -> {
				servicio.crear(admin, solicitud(DNI_CRUDO, CUIL_CRUDO), DATOS);
				aInserto.countDown();
				esperar(bEsperando);
				return "confirmada";
			}));
			assertThat(aInserto.await(10, TimeUnit.SECONDS)).isTrue();
			Future<Object> perdedora = hilos.submit(() -> {
				try {
					return servicio.crear(admin, solicitud(DNI, CUIL), DATOS);
				} catch (ExcepcionNegocio e) {
					return e;
				}
			});
			esperarQueUnaConexionEsteBloqueada();
			bEsperando.countDown();

			assertThat(alta.get(10, TimeUnit.SECONDS)).isEqualTo("confirmada");
			Object resultado = perdedora.get(10, TimeUnit.SECONDS);
			assertThat(resultado).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
				assertThat(e.getCodigo()).isEqualTo("DNI_DUPLICADO");
				assertThat(e.getMessage()).isEqualTo("Ya existe un deportista con ese DNI.");
			});
		} finally {
			bEsperando.countDown();
			hilos.shutdownNow();
			logs.stop();
		}

		assertThat(contar("deportista", escuelaA)).isEqualTo(1);
		// Una sola alta confirmada: una sola auditoria DEPORTISTA_CREADO y ninguna de la perdedora.
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'DEPORTISTA_CREADO'")
				.param("e", escuelaA).query(Long.class).single()).isEqualTo(1);
		// Observabilidad saneada de la carrera perdida: UN WARN con la restriccion, la operacion y la clase; sin valores.
		assertThat(logs.list.stream().filter(l -> l.getLoggerName().endsWith("RestriccionViolada")))
				.singleElement().satisfies(l -> {
					assertThat(l.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
					assertThat(l.getFormattedMessage()).isEqualTo("Violacion de restriccion mapeada a conflicto: "
							+ "restriccion=uq_deportista_dni_escuela operacion=DeportistaAdminService.crear "
							+ "excepcion=org.springframework.dao.DataIntegrityViolationException");
					assertThat(l.getThrowableProxy()).isNull();
				});
		for (ILoggingEvent evento : logs.list) {
			assertThat(evento.getFormattedMessage()).doesNotContain("duplicate key").doesNotContain("Detail:")
					.doesNotContain("Key (").doesNotContain(escuelaA.toString());
		}
		sinDocumentosEnLogs(logs);
	}

	/** Misma carrera pero por CUIL (DNI distintos): la base rechaza a la perdedora con CUIL_DUPLICADO. */
	@Test
	void unaAltaBloqueadaPorElCuilPierdeConCuilDuplicado() throws Exception {
		ExecutorService hilos = Executors.newFixedThreadPool(2);
		CountDownLatch aInserto = new CountDownLatch(1);
		CountDownLatch bEsperando = new CountDownLatch(1);
		try {
			Future<Object> alta = hilos.submit(() -> transaccion.execute(estado -> {
				servicio.crear(admin, solicitud(DNI, CUIL), DATOS);
				aInserto.countDown();
				esperar(bEsperando);
				return "confirmada";
			}));
			assertThat(aInserto.await(10, TimeUnit.SECONDS)).isTrue();
			Future<Object> perdedora = hilos.submit(() -> {
				try {
					return servicio.crear(admin, solicitud("36789077", CUIL), DATOS);
				} catch (ExcepcionNegocio e) {
					return e;
				}
			});
			esperarQueUnaConexionEsteBloqueada();
			bEsperando.countDown();

			assertThat(alta.get(10, TimeUnit.SECONDS)).isEqualTo("confirmada");
			assertThat(perdedora.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExcepcionNegocio.class,
					e -> assertThat(e.getCodigo()).isEqualTo("CUIL_DUPLICADO"));
		} finally {
			bEsperando.countDown();
			hilos.shutdownNow();
		}

		assertThat(contar("deportista", escuelaA)).isEqualTo(1);
	}

	@Test
	void dosHilosQueDanDeAltaElMismoDniDanExactamenteUna201YUna409NuncaUn500() throws Exception {
		for (int ronda = 0; ronda < 15; ronda++) {
			String dni = String.format("37%06d", ronda);
			ExecutorService hilos = Executors.newFixedThreadPool(2);
			CountDownLatch salida = new CountDownLatch(1);
			try {
				List<Future<Object>> futuros = new ArrayList<>();
				for (int i = 0; i < 2; i++) {
					futuros.add(hilos.submit((Callable<Object>) () -> {
						salida.await();
						try {
							return servicio.crear(admin, solicitud(dni, null), DATOS);
						} catch (ExcepcionNegocio e) {
							return e;
						}
					}));
				}
				salida.countDown();

				long creadas = 0;
				long conflictos = 0;
				for (Future<Object> futuro : futuros) {
					Object resultado = futuro.get(20, TimeUnit.SECONDS);
					if (resultado instanceof ExcepcionNegocio e) {
						assertThat(e.getCodigo()).isEqualTo("DNI_DUPLICADO");
						conflictos++;
					} else {
						creadas++;
					}
				}
				assertThat(creadas).as("ronda %d", ronda).isEqualTo(1);
				assertThat(conflictos).as("ronda %d", ronda).isEqualTo(1);
			} finally {
				hilos.shutdownNow();
			}
		}

		assertThat(contar("deportista", escuelaA)).isEqualTo(15);
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'DEPORTISTA_CREADO'")
				.param("e", escuelaA).query(Long.class).single()).isEqualTo(15);
	}

	// ---------- auditoria ----------

	@Test
	void laAuditoriaGuardaIdsFlagsYNombresDeCamposPeroNingunDocumentoNiDatoPersonal() {
		UUID id = servicio.crear(admin, solicitud(DNI_CRUDO, CUIL_CRUDO), DATOS).id();
		servicio.actualizar(admin, id, new DeportistaSolicitud(DNI_CRUDO, "Zuleica", "Quintana", CUIL_CRUDO, null, null,
				"Calle Secreta 123", null, "Banfield", null, null, "11-4444-5555", "zuleica@example.com"), DATOS);
		servicio.actualizar(admin, id, solicitud("36789099", CUIL_CRUDO), DATOS);
		servicio.actualizar(admin, id, solicitud("36789099", CUIL_CRUDO), DATOS); // identica: sin auditoria
		servicio.desactivar(admin, id, DATOS);
		servicio.desactivar(admin, id, DATOS); // repetida: sin auditoria
		servicio.activar(admin, id, DATOS);
		servicio.activar(admin, id, DATOS); // repetida: sin auditoria

		List<Map<String, Object>> eventos = jdbc.sql("""
				SELECT accion, usuario_id, recurso_tipo, recurso_id, detalle::text AS detalle
				FROM gestion_patin.auditoria WHERE escuela_id = :e ORDER BY id
				""").param("e", escuelaA).query().listOfRows();
		assertThat(eventos).extracting(e -> e.get("accion")).containsExactly("DEPORTISTA_CREADO",
				"DEPORTISTA_ACTUALIZADO", "DEPORTISTA_ACTUALIZADO", "DEPORTISTA_DESACTIVADO", "DEPORTISTA_ACTIVADO");
		for (Map<String, Object> evento : eventos) {
			assertThat(evento.get("usuario_id")).isEqualTo(adminId);
			assertThat(evento.get("recurso_tipo")).isEqualTo("DEPORTISTA");
			assertThat(evento.get("recurso_id")).isEqualTo(id);
			assertThat((String) evento.get("detalle")).doesNotContain(DNI).doesNotContain(DNI_CRUDO).doesNotContain("36789099")
					.doesNotContain(CUIL).doesNotContain(CUIL_CRUDO).doesNotContain("Zuleica").doesNotContain("Quintana")
					.doesNotContain("Calle Secreta").doesNotContain("11-4444-5555").doesNotContain("zuleica@example.com")
					.doesNotContain("Banfield");
		}
		assertThat((String) eventos.get(0).get("detalle")).contains("conCuil").contains("true");
		assertThat((String) eventos.get(1).get("detalle")).contains("camposModificados").contains("localidad");
		assertThat((String) eventos.get(2).get("detalle")).contains("camposModificados").contains("dni")
				.contains("localidad");
	}

	@Test
	void unConflictoNoDejaAuditoriaNiCambiosYUnPutIdenticoNoEscribeNada() {
		UUID id = servicio.crear(admin, solicitud(DNI, CUIL), DATOS).id();
		datos.deportista(escuelaA, "36789014", "Otro", "Activo");
		long auditoriasAntes = contar("auditoria", escuelaA);
		Map<String, Object> antes = jdbc.sql("SELECT * FROM gestion_patin.deportista WHERE id = :id").param("id", id).query()
				.singleRow();

		assertThatThrownBy(() -> servicio.actualizar(admin, id, solicitud("36789014", CUIL), DATOS))
				.satisfies(e -> assertError(e, "DNI_DUPLICADO"));
		assertThatThrownBy(() -> servicio.crear(admin, solicitud(DNI, null), DATOS))
				.satisfies(e -> assertError(e, "DNI_DUPLICADO"));
		servicio.actualizar(admin, id, solicitud(DNI, CUIL), DATOS);

		assertThat(contar("auditoria", escuelaA)).isEqualTo(auditoriasAntes);
		assertThat(jdbc.sql("SELECT * FROM gestion_patin.deportista WHERE id = :id").param("id", id).query().singleRow())
				.isEqualTo(antes);
	}

	// ---------- logs ----------

	@Test
	void ningunLogDeAltaEdicionNiConflictoContieneDniNiCuil() {
		ListAppender<ILoggingEvent> logs = capturarLogs();
		try {
			UUID id = servicio.crear(admin, solicitud(DNI_CRUDO, CUIL_CRUDO), DATOS).id();
			servicio.actualizar(admin, id, solicitud("36.789.099", CUIL_CRUDO), DATOS);
			assertThatThrownBy(() -> servicio.crear(admin, solicitud("36.789.099", null), DATOS))
					.isInstanceOf(ExcepcionNegocio.class);
			servicio.crear(adminDeB, solicitud(DNI, CUIL), DATOS);
		} finally {
			logs.stop();
		}

		sinDocumentosEnLogs(logs);
	}

	// ---------- auxiliares ----------

	private ListAppender<ILoggingEvent> capturarLogs() {
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
		return logs;
	}

	private void sinDocumentosEnLogs(ListAppender<ILoggingEvent> logs) {
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
		for (ILoggingEvent evento : logs.list) {
			String texto = evento.getFormattedMessage() + " " + evento.getThrowableProxy();
			assertThat(texto).as("log de %s", evento.getLoggerName()).doesNotContain(DNI).doesNotContain(DNI_CRUDO)
					.doesNotContain(CUIL).doesNotContain(CUIL_CRUDO).doesNotContain("36789099");
		}
	}

	private static void esperar(CountDownLatch latch) {
		try {
			if (!latch.await(15, TimeUnit.SECONDS)) {
				throw new IllegalStateException("tiempo agotado esperando a la alta bloqueada");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/** Espera (hasta 10 s) a que alguna sesion de esta base este esperando un bloqueo (la alta detras de la otra). */
	private void esperarQueUnaConexionEsteBloqueada() throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			long esperando = jdbc.sql("""
					SELECT count(*) FROM pg_stat_activity
					WHERE datname = current_database() AND wait_event_type = 'Lock'
					""").query(Long.class).single();
			if (esperando > 0) {
				return;
			}
			Thread.sleep(100);
		}
		throw new IllegalStateException("ninguna sesion quedo esperando el bloqueo del indice unico");
	}
}
