package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.familias.vinculos.VinculoAdminService;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.persistence.EntityManagerFactory;

/**
 * Datos y ayudas comunes de las pruebas de vinculos contra PostgreSQL real. Las subclases repiten las anotaciones de
 * contexto (@PruebaDb, @Import, @TestPropertySource) IDENTICAS entre si para compartir un unico contexto cacheado (el
 * contenedor tiene max_connections acotado y cada contexto guarda su pool).
 */
abstract class BaseVinculosDb extends BaseDbTest {

	static final DatosSolicitud DATOS = new DatosSolicitud("10.0.0.9", "JUnit");

	@Autowired
	JdbcClient jdbc;
	@Autowired
	VinculoAdminService servicio;
	@Autowired
	FamiliaDeportistaRepository repositorio;
	@Autowired
	TransactionTemplate transaccion;
	@Autowired
	EntityManagerFactory emf;

	ExecutorService hilos;
	DatosDb datos;
	UUID escuelaA;
	UUID escuelaB;
	UUID adminId;
	UsuarioAutenticado admin;
	UsuarioAutenticado adminDeB;

	@BeforeEach
	void prepararBase() {
		hilos = Executors.newFixedThreadPool(3);
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("vinc-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("vinc-b-" + UUID.randomUUID());
		adminId = datos.admin(escuelaA, "admin@a.example", true);
		admin = new UsuarioAutenticado(adminId, escuelaA, null, Rol.ADMIN);
		adminDeB = new UsuarioAutenticado(datos.admin(escuelaB, "admin@b.example", true), escuelaB, null, Rol.ADMIN);
	}

	@AfterEach
	void limpiarBase() {
		hilos.shutdownNow();
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	/** DNI de 8 digitos distinto por llamada dentro de una prueba (el DNI es unico por escuela). */
	private int contadorDni = 40000000;

	UUID deportista(UUID escuela, String nombre, boolean activo) {
		return datos.deportista(escuela, String.valueOf(contadorDni++), nombre, "Apellido" + nombre, activo);
	}

	UUID deportista(String nombre) {
		return deportista(escuelaA, nombre, true);
	}

	long sentencias(Runnable accion) {
		var estadisticas = emf.unwrap(SessionFactory.class).getStatistics();
		estadisticas.clear();
		accion.run();
		return estadisticas.getPrepareStatementCount();
	}

	long contar(String tabla, UUID escuela) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin." + tabla + " WHERE escuela_id = :e").param("e", escuela)
				.query(Long.class).single();
	}

	long contarAuditoria(String accion) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = :a")
				.param("e", escuelaA).param("a", accion).query(Long.class).single();
	}

	/** Fila cruda del vinculo (familia, deportista); vacia si no existe. */
	java.util.Optional<java.util.Map<String, Object>> fila(UUID familiaId, UUID deportistaId) {
		return jdbc.sql("SELECT * FROM gestion_patin.familia_deportista WHERE familia_id = :f AND deportista_id = :d")
				.param("f", familiaId).param("d", deportistaId).query().listOfRows().stream().findFirst();
	}

	List<java.util.Map<String, Object>> filas(UUID deportistaId) {
		return jdbc.sql("SELECT * FROM gestion_patin.familia_deportista WHERE deportista_id = :d ORDER BY id::text")
				.param("d", deportistaId).query().listOfRows();
	}

	long principalesActivos(UUID deportistaId) {
		return jdbc.sql("""
				SELECT count(*) FROM gestion_patin.familia_deportista
				WHERE deportista_id = :d AND estado = 'ACTIVO' AND es_principal
				""").param("d", deportistaId).query(Long.class).single();
	}

	/**
	 * Invariantes que NO pueden romperse tras ninguna carrera: a lo sumo un ACTIVO principal por deportista, una sola fila
	 * por (familia, deportista), todo ACTIVO con autorizacion y ningun principal fuera de ACTIVO.
	 */
	void verificarInvariantes() {
		org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
				SELECT count(*) FROM (
				  SELECT deportista_id FROM gestion_patin.familia_deportista
				  WHERE escuela_id = :e AND estado = 'ACTIVO' AND es_principal GROUP BY deportista_id HAVING count(*) > 1) t
				""").param("e", escuelaA).query(Long.class).single()).as("deportistas con mas de un ACTIVO principal").isZero();
		org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
				SELECT count(*) FROM (
				  SELECT 1 FROM gestion_patin.familia_deportista WHERE escuela_id = :e
				  GROUP BY familia_id, deportista_id HAVING count(*) > 1) t
				""").param("e", escuelaA).query(Long.class).single()).as("pares con mas de una fila").isZero();
		org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
				SELECT count(*) FROM gestion_patin.familia_deportista
				WHERE escuela_id = :e AND estado = 'ACTIVO' AND (autorizado_por IS NULL OR autorizado_en IS NULL)
				""").param("e", escuelaA).query(Long.class).single()).as("ACTIVO sin autorizacion").isZero();
		org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
				SELECT count(*) FROM gestion_patin.familia_deportista
				WHERE escuela_id = :e AND estado = 'REVOCADO' AND es_principal
				""").param("e", escuelaA).query(Long.class).single()).as("REVOCADO principal").isZero();
	}

	// ---------- ayudas de concurrencia ----------

	record Par(Object a, Object b) {
	}

	/** Ejecuta las tareas a la vez (salida sincronizada); un error de negocio es un resultado, cualquier otro hace fallar. */
	@SafeVarargs
	final List<Object> enParalelo(Supplier<Object>... tareas) throws Exception {
		CountDownLatch salida = new CountDownLatch(1);
		List<Future<Object>> futuros = new ArrayList<>();
		for (Supplier<Object> tarea : tareas) {
			futuros.add(hilos.submit(() -> {
				salida.await();
				try {
					return tarea.get();
				} catch (ExcepcionNegocio e) {
					return e;
				}
			}));
		}
		salida.countDown();
		List<Object> resultados = new ArrayList<>();
		for (Future<Object> futuro : futuros) {
			resultados.add(futuro.get(30, TimeUnit.SECONDS));
		}
		return resultados;
	}

	/**
	 * A opera dentro de una transaccion que NO confirma hasta que B esta realmente bloqueada esperando un bloqueo de la base;
	 * entonces A confirma y se devuelven los dos resultados (los errores de negocio de B son un resultado).
	 */
	Par bBloqueadaDetrasDeA(Supplier<Object> a, Supplier<Object> b) throws Exception {
		CountDownLatch aHizo = new CountDownLatch(1);
		CountDownLatch confirmar = new CountDownLatch(1);
		Future<Object> futuroA = hilos.submit(() -> transaccion.execute(estado -> {
			Object resultado = a.get();
			aHizo.countDown();
			esperar(confirmar);
			return resultado;
		}));
		while (!aHizo.await(100, TimeUnit.MILLISECONDS)) {
			if (futuroA.isDone()) {
				futuroA.get(); // propaga el fallo de A
			}
		}
		Future<Object> futuroB = hilos.submit(() -> {
			try {
				return b.get();
			} catch (ExcepcionNegocio e) {
				return e;
			}
		});
		try {
			esperarQueUnaConexionEsteBloqueada();
			assertThat(futuroB.isDone()).as("B debe seguir bloqueada mientras A no confirma").isFalse();
		} finally {
			confirmar.countDown();
		}
		return new Par(futuroA.get(15, TimeUnit.SECONDS), futuroB.get(15, TimeUnit.SECONDS));
	}

	static void esperar(CountDownLatch latch) {
		try {
			if (!latch.await(20, TimeUnit.SECONDS)) {
				throw new IllegalStateException("tiempo agotado esperando la confirmacion");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/** Espera (hasta 10 s) a que una sesion de esta base este esperando un bloqueo. */
	void esperarQueUnaConexionEsteBloqueada() throws InterruptedException {
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
		throw new IllegalStateException("ninguna sesion quedo esperando el bloqueo de fila del deportista");
	}

}
