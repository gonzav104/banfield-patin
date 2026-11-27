package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Prueba del helper de las pruebas de concurrencia: la sesion «bloqueada» se identifica por su relacion con la transaccion
 * retenida ({@code pg_blocking_pids}), no por ser «la primera sesion que espere algun bloqueo». Todas las pruebas con base comparten
 * la misma y sus contextos siguen vivos, asi que otra sesion puede estar esperando OTRA cosa a la vez: aqui se la fabrica a
 * proposito (un lector que espera un bloqueo de tabla ajeno) y se comprueba que ya no se confunde con B.
 */
@PruebaDb
@Import(ConfigTemporadasDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=6", "spring.datasource.hikari.minimum-idle=1" })
class SesionBloqueadaIdentificadaDbTest extends BaseTemporadasDb {

	@Autowired
	DataSource dataSource;

	private long sesionesEsperandoCualquierBloqueo() {
		return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'")
				.query(Long.class).single();
	}

	private long sesionesBloqueadasPor(Retencion a) {
		return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND :pid = ANY(pg_blocking_pids(pid))")
				.param("pid", a.pid()).query(Long.class).single();
	}

	private void esperarHasta(java.util.function.BooleanSupplier condicion, String que) throws InterruptedException {
		long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condicion.getAsBoolean()) {
			if (System.nanoTime() > limite) {
				throw new AssertionError("no se alcanzo: " + que);
			}
			Thread.sleep(25);
		}
	}

	private Retencion retenerLaEscuela() throws Exception {
		return new Retencion(() -> jdbc.sql("SELECT id FROM gestion_patin.escuela WHERE id = :e FOR UPDATE")
				.param("e", escuelaA).query(UUID.class).single());
	}

	@Test
	void unaSesionQueEsperaOtroBloqueoNoSeConfundeConLaBloqueadaPorA() throws Exception {
		try (Connection ajena = dataSource.getConnection()) {
			ajena.setAutoCommit(false);
			try (Statement st = ajena.createStatement()) {
				// Una tabla que no usan ni A ni B: el distractor espera un bloqueo que no tiene nada que ver con ellos.
				st.execute("LOCK TABLE gestion_patin.usuario_mfa IN ACCESS EXCLUSIVE MODE");
			}
			Future<Object> distractor = hilos.submit(() -> jdbc.sql("SELECT count(*) FROM gestion_patin.usuario_mfa").query(Long.class).single());
			esperarHasta(() -> sesionesEsperandoCualquierBloqueo() >= 1, "el lector del distractor esperando el bloqueo de tabla");

			try (Retencion a = retenerLaEscuela()) {
				Future<Object> b = enOtroHilo(() -> datos.familia(escuelaA, "Bloqueada por A", true));
				esperarHasta(() -> sesionesBloqueadasPor(a) == 1, "B bloqueada por A");

				// La condicion de la falla de CI: hay MAS DE UNA sesion esperando «algun» bloqueo, y la que dice el criterio viejo
				// (la primera, sin orden garantizado) puede ser el distractor.
				assertThat(sesionesEsperandoCualquierBloqueo()).isGreaterThanOrEqualTo(2);

				String sentencia = assertBloqueada(a, b);
				assertThat(sentencia).contains("gestion_patin.familia").doesNotContain("usuario_mfa");

				a.confirmar();
				assertThat(b.get(15, TimeUnit.SECONDS)).isInstanceOf(UUID.class);
			} finally {
				ajena.rollback();
			}
			assertThat(distractor.get(15, TimeUnit.SECONDS)).isEqualTo(0L);
		}
	}

	@Test
	void siAbloqueaADosSesionesFallaConElDetalleEnVezDeElegirUna() throws Exception {
		try (Retencion a = retenerLaEscuela()) {
			Future<Object> b1 = enOtroHilo(() -> datos.familia(escuelaA, "Primera bloqueada", true));
			Future<Object> b2 = enOtroHilo(() -> datos.familia(escuelaA, "Segunda bloqueada", true));
			esperarHasta(() -> sesionesBloqueadasPor(a) == 2, "las dos sesiones bloqueadas por A");

			assertThatThrownBy(() -> sentenciaBloqueadaPor(a)).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("exactamente una sesion bloqueada").hasMessageContaining("hay 2");

			a.confirmar();
			assertThat(b1.get(15, TimeUnit.SECONDS)).isInstanceOf(UUID.class);
			assertThat(b2.get(15, TimeUnit.SECONDS)).isInstanceOf(UUID.class);
		}
	}
}
