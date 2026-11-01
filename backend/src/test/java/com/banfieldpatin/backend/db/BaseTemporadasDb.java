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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.TemporadaAdminService;
import com.banfieldpatin.backend.usuarios.Rol;

/**
 * Datos y ayudas comunes de las pruebas de temporadas contra PostgreSQL real. Las subclases repiten las anotaciones de
 * contexto IDENTICAS entre si para compartir un unico contexto cacheado (el contenedor tiene max_connections acotado).
 */
abstract class BaseTemporadasDb extends BaseDbTest {

	static final DatosSolicitud DATOS = new DatosSolicitud("10.0.0.9", "JUnit");

	@Autowired
	JdbcClient jdbc;
	@Autowired
	TemporadaAdminService servicio;
	@Autowired
	TransactionTemplate transaccion;
	@Autowired
	jakarta.persistence.EntityManagerFactory emf;

	ExecutorService hilos;
	DatosDb datos;
	UUID escuelaA;
	UUID escuelaB;
	UsuarioAutenticado admin;
	UsuarioAutenticado adminDeB;

	@BeforeEach
	void prepararBase() {
		hilos = Executors.newFixedThreadPool(4);
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("temp-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("temp-b-" + UUID.randomUUID());
		admin = new UsuarioAutenticado(datos.admin(escuelaA, "admin@a.example", true), escuelaA, null, Rol.ADMIN);
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

	UUID deportista(UUID escuela, String nombre, String apellido, boolean activo) {
		return datos.deportista(escuela, String.valueOf(contadorDni++), nombre, apellido, activo);
	}

	/** Sentencias SQL que prepara Hibernate al ejecutar la accion (requiere generate_statistics=true). */
	long sentencias(Runnable accion) {
		var estadisticas = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
		estadisticas.clear();
		accion.run();
		return estadisticas.getPrepareStatementCount();
	}

	long contarAuditoria(UUID escuela, String accion) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = :a")
				.param("e", escuela).param("a", accion).query(Long.class).single();
	}

	boolean activa(UUID temporadaId) {
		return jdbc.sql("SELECT activa FROM gestion_patin.temporada WHERE id = :id").param("id", temporadaId)
				.query(Boolean.class).single();
	}

	List<UUID> activasDe(UUID escuela) {
		return jdbc.sql("SELECT id FROM gestion_patin.temporada WHERE escuela_id = :e AND activa").param("e", escuela)
				.query(UUID.class).list();
	}

	/** Instante de la ultima modificacion (lo fija el trigger solo en un UPDATE real). */
	Object actualizadoEn(UUID temporadaId) {
		return jdbc.sql("SELECT actualizado_en FROM gestion_patin.temporada WHERE id = :id").param("id", temporadaId)
				.query().singleValue();
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

	/** Una transaccion que hace su operacion, avisa y NO confirma hasta que se libere. */
	final class Retencion implements AutoCloseable {

		private final CountDownLatch hizo = new CountDownLatch(1);
		private final CountDownLatch confirmar = new CountDownLatch(1);
		private final Future<Object> futuro;

		Retencion(Supplier<Object> operacion) throws Exception {
			futuro = hilos.submit(() -> transaccion.execute(estado -> {
				Object resultado = operacion.get();
				hizo.countDown();
				esperar(confirmar);
				return resultado;
			}));
			while (!hizo.await(100, TimeUnit.MILLISECONDS)) {
				if (futuro.isDone()) {
					futuro.get(); // propaga el fallo
				}
			}
		}

		/** Confirma y devuelve el resultado de la operacion. */
		Object confirmar() throws Exception {
			confirmar.countDown();
			return futuro.get(15, TimeUnit.SECONDS);
		}

		@Override
		public void close() {
			confirmar.countDown();
		}
	}

	Future<Object> enOtroHilo(Supplier<Object> tarea) {
		return hilos.submit(() -> {
			try {
				return tarea.get();
			} catch (ExcepcionNegocio e) {
				return e;
			}
		});
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

	/** Espera (hasta 10 s) a que una sesion de esta base este esperando un bloqueo y devuelve la sentencia que espera. */
	String sentenciaEsperandoUnBloqueo() throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			List<String> esperando = jdbc.sql("""
					SELECT query FROM pg_stat_activity
					WHERE datname = current_database() AND wait_event_type = 'Lock'
					""").query(String.class).list();
			if (!esperando.isEmpty()) {
				return esperando.get(0);
			}
			Thread.sleep(100);
		}
		throw new IllegalStateException("ninguna sesion quedo esperando un bloqueo");
	}

	/** B (en otro hilo) queda bloqueada de verdad detras de la retencion de A; devuelve la sentencia que espera. */
	String assertBloqueada(Future<Object> b) throws InterruptedException {
		String sentencia = sentenciaEsperandoUnBloqueo();
		assertThat(b.isDone()).as("B debe seguir bloqueada mientras A no confirma").isFalse();
		return sentencia;
	}
}
