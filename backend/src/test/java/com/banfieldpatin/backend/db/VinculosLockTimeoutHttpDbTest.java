package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.usuarios.Rol;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;

/**
 * lock_timeout de vinculos por HTTP (cadena de seguridad completa, servicios y manejador REALES, PostgreSQL 17
 * descartable): con el deportista retenido por una transaccion A sin confirmar, vincular / revocar / cambiar principal
 * responden 409 CONFLICTO_CONCURRENCIA con un cuerpo FIJO (sin SQL, ids, tablas ni clases), no escriben ni auditan, dejan
 * UN solo WARN saneado (ningun evento ERROR ni con traza) y, tras confirmar A, la misma solicitud responde 200.
 * Plazo de 300 ms; pool de 3 conexiones (A, la solicitud y el muestreo de pg_stat_activity).
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1",
		"banfield.vinculos.lock-timeout=PT0.3S" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
class VinculosLockTimeoutHttpDbTest extends BaseDbTest {

	private static final String CUERPO_ESPERADO = "{\"codigo\":\"CONFLICTO_CONCURRENCIA\",\"mensaje\":"
			+ "\"Otro cambio sobre el mismo deportista esta en curso. Reintentá en unos segundos.\",\"detalles\":[]}";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	TransactionTemplate transaccion;
	@Autowired
	DeportistaRepository deportistas;

	private DatosDb datos;
	private UUID escuelaId;
	private UUID adminId;
	private UUID f1;
	private UUID f2;
	private UUID d;
	private ExecutorService hilos;
	private Logger raiz;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaId = datos.escuela("lt-http-" + UUID.randomUUID());
		adminId = datos.admin(escuelaId, "admin@lt.example", true);
		f1 = datos.familia(escuelaId, "Uno", true);
		f2 = datos.familia(escuelaId, "Dos", true);
		d = datos.deportista(escuelaId, "41000001", "Nombre", "Apellido");
		hilos = Executors.newFixedThreadPool(2);
		raiz = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		logs = new ListAppender<>();
		logs.start();
		raiz.addAppender(logs);
	}

	@AfterEach
	void limpiar() {
		raiz.detachAppender(logs);
		hilos.shutdownNow();
		datos.limpiarEscuela(escuelaId);
	}

	private MockHttpServletRequestBuilder conSesion(MockHttpServletRequestBuilder req, String json) throws Exception {
		Cookie xsrf = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/csrf"))
				.andReturn().getResponse().getCookie("XSRF-TOKEN");
		Cookie sesion = new Cookie("BP_SESION", tokens.emitir(adminId, Rol.ADMIN, escuelaId, null));
		var r = req.cookie(xsrf, sesion).header("X-XSRF-TOKEN", xsrf.getValue());
		return json == null ? r : r.contentType("application/json").content(json);
	}

	private MockHttpServletResponse vincular(UUID familia) throws Exception {
		return mvc.perform(conSesion(post("/api/admin/familias/" + familia + "/deportistas"),
				"{\"deportistaIds\":[\"" + d + "\"]}")).andReturn().getResponse();
	}

	private MockHttpServletResponse revocar(UUID familia) throws Exception {
		return mvc.perform(conSesion(post("/api/admin/familias/" + familia + "/deportistas/" + d + "/revocar"), null))
				.andReturn().getResponse();
	}

	private MockHttpServletResponse principal(UUID familia) throws Exception {
		return mvc.perform(conSesion(post("/api/admin/familias/" + familia + "/deportistas/" + d + "/principal"), null))
				.andReturn().getResponse();
	}

	@FunctionalInterface
	private interface Solicitud {
		MockHttpServletResponse ejecutar() throws Exception;
	}

	private long contar(String sql) {
		return jdbc.sql(sql).param("e", escuelaId).query(Long.class).single();
	}

	private long auditoria() {
		return contar("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion LIKE 'VINCULO_%'");
	}

	private long vinculos() {
		return contar("SELECT count(*) FROM gestion_patin.familia_deportista WHERE escuela_id = :e");
	}

	/** A retiene la fila del deportista; la solicitud espera de verdad (pg_stat_activity) y vence; A confirma. */
	private MockHttpServletResponse conElDeportistaRetenido(Solicitud solicitud) throws Exception {
		CountDownLatch retenido = new CountDownLatch(1);
		CountDownLatch liberar = new CountDownLatch(1);
		Future<Object> a = hilos.submit(() -> transaccion.execute(estado -> {
			deportistas.bloquearParaVincular(escuelaId, List.of(d));
			retenido.countDown();
			try {
				liberar.await(20, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return null;
		}));
		try {
			assertThat(retenido.await(10, TimeUnit.SECONDS)).isTrue();
			Future<MockHttpServletResponse> b = hilos.submit(solicitud::ejecutar);
			esperarBloqueo();
			assertThat(b.isDone()).as("la solicitud debe estar esperando el bloqueo de A").isFalse();
			return b.get(10, TimeUnit.SECONDS);
		} finally {
			liberar.countDown();
			a.get(10, TimeUnit.SECONDS);
		}
	}

	private void esperarBloqueo() throws InterruptedException {
		for (int i = 0; i < 500; i++) {
			long esperando = jdbc.sql("""
					SELECT count(*) FROM pg_stat_activity
					WHERE datname = current_database() AND wait_event_type = 'Lock'
					""").query(Long.class).single();
			if (esperando > 0) {
				return;
			}
			Thread.sleep(20);
		}
		throw new IllegalStateException("ninguna sesion quedo esperando el bloqueo de fila del deportista");
	}

	private void soloElConflictoSaneado(String operacionEsperada) {
		List<ILoggingEvent> manejador = logs.list.stream()
				.filter(e -> e.getLoggerName().endsWith("ManejadorGlobalErrores")).toList();
		assertThat(manejador).singleElement().satisfies(e -> {
			assertThat(e.getLevel()).isEqualTo(Level.WARN);
			assertThat(e.getFormattedMessage()).isEqualTo("Conflicto de concurrencia por bloqueo de base de datos (409): "
					+ "operacion=" + operacionEsperada + " excepcion=org.springframework.dao.CannotAcquireLockException");
			assertThat(e.getThrowableProxy()).isNull();
		});
		for (ILoggingEvent evento : logs.list) {
			String texto = evento.getFormattedMessage() + " " + evento.getThrowableProxy() + " "
					+ java.util.Arrays.toString(evento.getArgumentArray());
			assertThat(texto).as("log de %s", evento.getLoggerName()).doesNotContain("could not obtain lock")
					.doesNotContain("lock timeout").doesNotContain("55P03").doesNotContain("for update")
					.doesNotContain("FOR UPDATE").doesNotContain("select ").doesNotContain("relation")
					.doesNotContain(d.toString()).doesNotContain(f1.toString()).doesNotContain(escuelaId.toString())
					.doesNotContain("Detail:");
		}
		assertThat(logs.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.ERROR));
	}

	private void cuerpoSaneado(MockHttpServletResponse r) throws Exception {
		assertThat(r.getStatus()).isEqualTo(409);
		assertThat(r.getContentType()).startsWith("application/json");
		String cuerpo = r.getContentAsString(StandardCharsets.UTF_8);
		assertThat(cuerpo).isEqualTo(CUERPO_ESPERADO);
		assertThat(cuerpo).doesNotContain(d.toString()).doesNotContain(f1.toString()).doesNotContain("SQL")
				.doesNotContain("lock timeout").doesNotContain("gestion_patin").doesNotContain("Exception")
				.doesNotContain("at com.");
	}

	@Test
	void vincularConElDeportistaRetenidoDa409ConflictoSaneadoNoEscribeYAlLiberarloDa200() throws Exception {
		MockHttpServletResponse r = conElDeportistaRetenido(() -> vincular(f1));

		cuerpoSaneado(r);
		assertThat(vinculos()).isZero();
		assertThat(auditoria()).isZero();
		soloElConflictoSaneado("POST /api/admin/familias/{familiaId}/deportistas");

		MockHttpServletResponse reintento = vincular(f1);
		assertThat(reintento.getStatus()).isEqualTo(200);
		assertThat(vinculos()).isEqualTo(1);
		assertThat(auditoria()).isEqualTo(1);
	}

	@Test
	void revocarConElDeportistaRetenidoDa409ConflictoSaneadoDejaElVinculoYAlLiberarloDa200() throws Exception {
		assertThat(vincular(f1).getStatus()).isEqualTo(200);
		logs.list.clear();
		long auditoriaInicial = auditoria();

		MockHttpServletResponse r = conElDeportistaRetenido(() -> revocar(f1));

		cuerpoSaneado(r);
		assertThat(contar("SELECT count(*) FROM gestion_patin.familia_deportista WHERE escuela_id = :e AND estado = 'ACTIVO'"))
				.isEqualTo(1);
		assertThat(auditoria()).isEqualTo(auditoriaInicial);
		soloElConflictoSaneado("POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/revocar");

		assertThat(revocar(f1).getStatus()).isEqualTo(200);
		assertThat(contar("SELECT count(*) FROM gestion_patin.familia_deportista WHERE escuela_id = :e AND estado = 'REVOCADO'"))
				.isEqualTo(1);
	}

	@Test
	void cambiarPrincipalConElDeportistaRetenidoDa409ConflictoSaneadoDejaElPrincipalYAlLiberarloDa200() throws Exception {
		assertThat(vincular(f1).getStatus()).isEqualTo(200);
		assertThat(vincular(f2).getStatus()).isEqualTo(200);
		logs.list.clear();
		long auditoriaInicial = auditoria();

		MockHttpServletResponse r = conElDeportistaRetenido(() -> principal(f2));

		cuerpoSaneado(r);
		assertThat(contar("SELECT count(*) FROM gestion_patin.familia_deportista WHERE escuela_id = :e AND es_principal"))
				.isEqualTo(1);
		assertThat(jdbc.sql("SELECT familia_id FROM gestion_patin.familia_deportista WHERE escuela_id = :e AND es_principal")
				.param("e", escuelaId).query(UUID.class).single()).isEqualTo(f1);
		assertThat(auditoria()).isEqualTo(auditoriaInicial);
		soloElConflictoSaneado("POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/principal");

		assertThat(principal(f2).getStatus()).isEqualTo(200);
		assertThat(jdbc.sql("SELECT familia_id FROM gestion_patin.familia_deportista WHERE escuela_id = :e AND es_principal")
				.param("e", escuelaId).query(UUID.class).single()).isEqualTo(f2);
	}

	@Test
	void sinContencionNingunaRespuestaEsUnConflicto() throws Exception {
		assertThat(vincular(f1).getStatus()).isEqualTo(200);
		assertThat(vincular(f2).getStatus()).isEqualTo(200);
		assertThat(principal(f2).getStatus()).isEqualTo(200);
		assertThat(revocar(f1).getStatus()).isEqualTo(200);
		assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("Conflicto de concurrencia"));
	}
}
