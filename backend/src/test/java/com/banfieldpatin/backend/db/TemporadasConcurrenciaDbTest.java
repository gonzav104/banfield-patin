package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.temporadas.dto.TemporadaRespuesta;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Activacion concurrente de temporadas contra PostgreSQL real. Tecnica determinista (como en vinculos): una transaccion A
 * activa SIN confirmar, B arranca en otro hilo y queda bloqueada de verdad en el bloqueo de la escuela (se sondea
 * {@code pg_stat_activity.wait_event_type = 'Lock'}), y solo entonces A confirma. Ademas, rondas de hilos libres con una
 * salida sincronizada. El bloqueo evita llegar al indice parcial: ni el WARN de una carrera traducida ni un ERROR deben
 * aparecer. Tambien prueba, contra la base, que el bloqueo es el previsto: {@code FOR NO KEY UPDATE} sobre la fila de la
 * escuela, que serializa las activaciones de ESA escuela y NO frena operaciones con claves foraneas hacia ella ni otras escuelas.
 */
@PruebaDb
@Import(ConfigTemporadasDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=5",
		"spring.datasource.hikari.minimum-idle=1" })
class TemporadasConcurrenciaDbTest extends BaseTemporadasDb {

	private static final int RONDAS = 15;
	private static final long SIN_ESPERA_SEGUNDOS = 5;

	private ListAppender<ILoggingEvent> logs;
	private Logger raiz;

	@BeforeEach
	void capturarLogs() {
		raiz = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		logs = new ListAppender<>();
		logs.start();
		raiz.addAppender(logs);
	}

	@AfterEach
	void liberar() {
		raiz.detachAppender(logs);
	}

	private void sinViolacionesNiErrores() {
		assertThat(logs.list).noneMatch(e -> e.getLoggerName().endsWith("RestriccionViolada"));
		assertThat(logs.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.ERROR));
	}

	// ---------- dos activaciones de temporadas distintas ----------

	@Test
	void dosActivacionesDeTemporadasDistintasUnaDetrasDeLaOtraAmbasTienenExitoYQuedaUnaSolaActivaLaUltima() throws Exception {
		UUID t1 = datos.temporada(escuelaA, 2026, false);
		UUID t2 = datos.temporada(escuelaA, 2027, false);

		Retencion a = new Retencion(() -> servicio.activar(admin, t1, DATOS));
		Future<Object> b = enOtroHilo(() -> servicio.activar(admin, t2, DATOS));
		String sentencia;
		try {
			sentencia = assertBloqueada(b);
		} finally {
			a.close();
		}

		assertThat(a.confirmar()).isEqualTo(new TemporadaRespuesta(t1, 2026, true));
		assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(new TemporadaRespuesta(t2, 2027, true));
		// SQL efectivo que recibio el servidor: FOR NO KEY UPDATE sobre la fila de la escuela, no FOR UPDATE.
		assertThat(sentencia.toLowerCase()).contains("gestion_patin.escuela").contains("for no key update");
		assertThat(activasDe(escuelaA)).containsExactly(t2);
		assertThat(activa(t1)).isFalse();
		assertThat(contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA")).isEqualTo(2);
		// B vio la activacion de A ya confirmada: su auditoria nombra a t1 como anterior.
		assertThat(jdbc.sql("""
				SELECT detalle->>'anteriorTemporadaId' FROM gestion_patin.auditoria
				WHERE escuela_id = :e AND accion = 'TEMPORADA_ACTIVADA' AND recurso_id = :r
				""").param("e", escuelaA).param("r", t2).query(String.class).single()).isEqualTo(t1.toString());
		sinViolacionesNiErrores();
	}

	@Test
	void rondasDeHilosLibresActivandoDosTemporadasDejanSiempreExactamenteUnaActiva() throws Exception {
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			UUID x = datos.temporada(escuelaA, 2030 + ronda * 2, false);
			UUID y = datos.temporada(escuelaA, 2031 + ronda * 2, false);

			List<Object> resultados = enParalelo(() -> servicio.activar(admin, x, DATOS),
					() -> servicio.activar(admin, y, DATOS));

			assertThat(resultados).as("ronda " + ronda).allSatisfy(r -> assertThat(r).isInstanceOf(TemporadaRespuesta.class));
			assertThat(activasDe(escuelaA)).as("ronda " + ronda).hasSize(1).containsAnyOf(x, y);
		}
		// Cada activacion real deja exactamente un evento: dos por ronda (la segunda es la que desactivo a la primera).
		assertThat(contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA")).isEqualTo(RONDAS * 2L);
		sinViolacionesNiErrores();
	}

	// ---------- la misma temporada dos veces ----------

	@Test
	void dosActivacionesDeLaMismaTemporadaUnaDetrasDeLaOtraAuditanUnaSolaVezYLaSegundaEsIdempotente() throws Exception {
		UUID previa = datos.temporada(escuelaA, 2026, true);
		UUID t = datos.temporada(escuelaA, 2027, false);

		Retencion a = new Retencion(() -> servicio.activar(admin, t, DATOS));
		Future<Object> b = enOtroHilo(() -> servicio.activar(admin, t, DATOS));
		try {
			assertBloqueada(b);
		} finally {
			a.close();
		}

		assertThat(a.confirmar()).isEqualTo(new TemporadaRespuesta(t, 2027, true));
		// B esperaba el bloqueo con la temporada inactiva; con el bloqueo tomado la releyo activa y no hizo nada.
		assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(new TemporadaRespuesta(t, 2027, true));
		assertThat(activasDe(escuelaA)).containsExactly(t);
		assertThat(activa(previa)).isFalse();
		assertThat(contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA")).isEqualTo(1);
		sinViolacionesNiErrores();
	}

	@Test
	void rondasDeHilosLibresActivandoLaMismaTemporadaAuditanExactamenteUnaVez() throws Exception {
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			UUID t = datos.temporada(escuelaA, 2030 + ronda, false);
			long antes = contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA");

			List<Object> resultados = enParalelo(() -> servicio.activar(admin, t, DATOS),
					() -> servicio.activar(admin, t, DATOS), () -> servicio.activar(admin, t, DATOS));

			assertThat(resultados).as("ronda " + ronda).allSatisfy(r -> assertThat(r).isEqualTo(new TemporadaRespuesta(t,
					2030 + (int) (jdbc.sql("SELECT anio FROM gestion_patin.temporada WHERE id = :i").param("i", t)
							.query(Integer.class).single() - 2030), true)));
			assertThat(activasDe(escuelaA)).as("ronda " + ronda).containsExactly(t);
			assertThat(contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA") - antes).as("ronda " + ronda).isEqualTo(1);
		}
		sinViolacionesNiErrores();
	}

	// ---------- que el bloqueo sea solo el de esa escuela y no frene claves foraneas ----------

	@Test
	void unaActivacionRetenidaEnUnaEscuelaNoFrenaLaActivacionDeOtraEscuela() throws Exception {
		UUID deA = datos.temporada(escuelaA, 2027, false);
		UUID deB = datos.temporada(escuelaB, 2027, false);

		try (Retencion a = new Retencion(() -> servicio.activar(admin, deA, DATOS))) {
			Object resultadoB = enOtroHilo(() -> servicio.activar(adminDeB, deB, DATOS)).get(SIN_ESPERA_SEGUNDOS, TimeUnit.SECONDS);

			assertThat(resultadoB).isEqualTo(new TemporadaRespuesta(deB, 2027, true));
			assertThat(activa(deA)).as("A todavia no confirmo").isFalse();
			a.confirmar();
		}
		assertThat(activasDe(escuelaA)).containsExactly(deA);
		assertThat(activasDe(escuelaB)).containsExactly(deB);
	}

	@Test
	void unaActivacionRetenidaNoFrenaOperacionesConClaveForaneaHaciaLaMismaEscuela() throws Exception {
		UUID deA = datos.temporada(escuelaA, 2027, false);

		try (Retencion a = new Retencion(() -> servicio.activar(admin, deA, DATOS))) {
			// Insertar una familia y una temporada toma FOR KEY SHARE sobre la fila de la escuela (comprobacion de la FK).
			Future<Object> familia = enOtroHilo(() -> datos.familia(escuelaA, "Mientras se activa", true));
			Future<Object> otraTemporada = enOtroHilo(() -> datos.temporada(escuelaA, 2028, false));
			Future<Object> alta = enOtroHilo(() -> servicio.crear(admin, new com.banfieldpatin.backend.temporadas.dto.TemporadaSolicitud(2029), DATOS));

			assertThat(familia.get(SIN_ESPERA_SEGUNDOS, TimeUnit.SECONDS)).isInstanceOf(UUID.class);
			assertThat(otraTemporada.get(SIN_ESPERA_SEGUNDOS, TimeUnit.SECONDS)).isInstanceOf(UUID.class);
			assertThat(alta.get(SIN_ESPERA_SEGUNDOS, TimeUnit.SECONDS)).isInstanceOf(TemporadaRespuesta.class);
			assertThat(activa(deA)).as("la activacion sigue sin confirmar").isFalse();
			a.confirmar();
		}
		assertThat(activasDe(escuelaA)).containsExactly(deA);
	}

	/**
	 * Control de sensibilidad de la prueba anterior: con {@code FOR UPDATE} (el bloqueo mas fuerte) la MISMA operacion con
	 * clave foranea SI queda esperando. Sin este control, la prueba anterior podria pasar aunque el bloqueo fuera cualquiera.
	 */
	@Test
	void controlConForUpdateUnaOperacionConClaveForaneaSiQuedaEsperando() throws Exception {
		Retencion a = new Retencion(() -> jdbc.sql("SELECT id FROM gestion_patin.escuela WHERE id = :e FOR UPDATE")
				.param("e", escuelaA).query(UUID.class).single());
		Future<Object> familia = enOtroHilo(() -> datos.familia(escuelaA, "Bloqueada por FOR UPDATE", true));
		try {
			assertBloqueada(familia);
		} finally {
			a.close();
		}
		a.confirmar();
		assertThat(familia.get(15, TimeUnit.SECONDS)).isInstanceOf(UUID.class);
	}
}
