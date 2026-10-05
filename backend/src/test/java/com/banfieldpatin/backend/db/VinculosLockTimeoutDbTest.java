package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.familias.vinculos.LockTimeoutVinculos;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculacionRespuesta;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta;

/**
 * lock_timeout de las transacciones de vinculos contra PostgreSQL real, con el plazo configurado en 300 ms
 * (banfield.vinculos.lock-timeout). Tecnica determinista de VinculosConcurrenciaDbTest: la transaccion A retiene la fila
 * del deportista SIN confirmar; B (vincular / revocar / cambiar principal) se queda de verdad esperando el bloqueo (se
 * sondea {@code pg_stat_activity.wait_event_type = 'Lock'}) y a los ~300 ms la base la corta con 55P03: el servicio
 * propaga {@code CannotAcquireLockException}, no queda nada escrito ni auditado y, cuando A confirma, la misma llamada
 * tiene exito. Pool de 3 conexiones: A, B y el muestreo. (La fuga entre transacciones del pool se prueba aparte, con un
 * pool de UNA conexion: VinculosLockTimeoutSinFugaDbTest.) La traduccion a 409 por HTTP esta en
 * VinculosLockTimeoutHttpDbTest.
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "banfield.vinculos.lock-timeout=PT0.3S" })
class VinculosLockTimeoutDbTest extends BaseVinculosDb {

	@Autowired
	DeportistaRepository deportistas;
	@Autowired
	LockTimeoutVinculos lockTimeout;

	/** Resultado de B: lo que lanzo (o devolvio) y cuanto tardo. */
	private record Intento(Throwable error, Object valor, long milisegundos) {
	}

	/**
	 * A retiene el bloqueo de fila del deportista hasta que B termina; B es la accion bajo prueba. Devuelve el intento de B
	 * (que debe terminar por timeout, no antes de empezar a esperar) con A todavia sin confirmar.
	 */
	private Intento conElDeportistaRetenido(UUID deportistaId, Supplier<Object> b) throws Exception {
		CountDownLatch retenido = new CountDownLatch(1);
		CountDownLatch liberar = new CountDownLatch(1);
		Future<Object> a = hilos.submit(() -> transaccion.execute(estado -> {
			deportistas.bloquearParaVincular(escuelaA, List.of(deportistaId));
			retenido.countDown();
			esperar(liberar);
			return null;
		}));
		try {
			while (!retenido.await(100, TimeUnit.MILLISECONDS)) {
				if (a.isDone()) {
					a.get();
				}
			}
			long inicio = System.nanoTime();
			Future<Intento> futuroB = hilos.submit(() -> {
				try {
					Object valor = b.get();
					return new Intento(null, valor, (System.nanoTime() - inicio) / 1_000_000);
				} catch (Throwable e) {
					return new Intento(e, null, (System.nanoTime() - inicio) / 1_000_000);
				}
			});
			esperarQueUnaConexionEsteBloqueada();
			assertThat(futuroB.isDone()).as("B debe estar esperando el bloqueo de A, no haber fallado antes").isFalse();
			return futuroB.get(10, TimeUnit.SECONDS);
		} finally {
			liberar.countDown();
			try {
				a.get(10, TimeUnit.SECONDS);
			} catch (ExecutionException e) {
				throw e;
			}
		}
	}

	private static void aTiempoDeTimeout(Intento intento) {
		assertThat(intento.error()).as("B debe vencer el lock_timeout").isInstanceOf(CannotAcquireLockException.class)
				.isInstanceOf(PessimisticLockingFailureException.class);
		// Esperó de verdad (~300 ms) y no quedo colgada indefinidamente (el pool/hilo de la prueba la habria perdido).
		assertThat(intento.milisegundos()).isBetween(250L, 5_000L);
	}

	@Test
	void vincularConElDeportistaRetenidoVenceElPlazoNoEscribeNiAuditaYAlLiberarloLaMismaLlamadaTieneExito() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");

		Intento b = conElDeportistaRetenido(d, () -> servicio.vincular(admin, f1, List.of(d), DATOS));

		aTiempoDeTimeout(b);
		assertThat(fila(f1, d)).as("nada escrito").isEmpty();
		assertThat(contarAuditoria("VINCULO_ACTIVADO")).isZero();
		verificarInvariantes();
		// Con A ya confirmado (sin retener nada), la misma llamada funciona: el limite no dejo estado raro.
		VinculacionRespuesta ok = servicio.vincular(admin, f1, List.of(d), DATOS);
		assertThat(ok.resultados()).singleElement().satisfies(r -> assertThat(r.vinculo().esPrincipal()).isTrue());
		assertThat(contarAuditoria("VINCULO_ACTIVADO")).isEqualTo(1);
		verificarInvariantes();
	}

	@Test
	void revocarConElDeportistaRetenidoVenceElPlazoDejaElVinculoIntactoYAlLiberarloTieneExito() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f1, List.of(d), DATOS);

		Intento b = conElDeportistaRetenido(d, () -> servicio.revocar(admin, f1, d, DATOS));

		aTiempoDeTimeout(b);
		assertThat(fila(f1, d).orElseThrow()).containsEntry("estado", "ACTIVO").containsEntry("es_principal", true);
		assertThat(contarAuditoria("VINCULO_REVOCADO")).isZero();
		verificarInvariantes();
		VinculoRespuesta ok = servicio.revocar(admin, f1, d, DATOS);
		assertThat(ok.estado().name()).isEqualTo("REVOCADO");
		assertThat(contarAuditoria("VINCULO_REVOCADO")).isEqualTo(1);
		verificarInvariantes();
	}

	@Test
	void cambiarPrincipalConElDeportistaRetenidoVenceElPlazoDejaLosPrincipalesIntactosYAlLiberarloTieneExito()
			throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.vincular(admin, f2, List.of(d), DATOS);

		Intento b = conElDeportistaRetenido(d, () -> servicio.cambiarPrincipal(admin, f2, d, DATOS));

		aTiempoDeTimeout(b);
		assertThat(fila(f1, d).orElseThrow()).containsEntry("es_principal", true);
		assertThat(fila(f2, d).orElseThrow()).containsEntry("es_principal", false);
		assertThat(contarAuditoria("VINCULO_PRINCIPAL_CAMBIADO")).isZero();
		verificarInvariantes();
		VinculoRespuesta ok = servicio.cambiarPrincipal(admin, f2, d, DATOS);
		assertThat(ok.esPrincipal()).isTrue();
		assertThat(fila(f1, d).orElseThrow()).containsEntry("es_principal", false);
		assertThat(principalesActivos(d)).isEqualTo(1);
		assertThat(contarAuditoria("VINCULO_PRINCIPAL_CAMBIADO")).isEqualTo(1);
		verificarInvariantes();
	}

	@Test
	void unLoteConUnDeportistaRetenidoVenceElPlazoYNoVinculaNingunoDeLosDemas() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID libre = deportista("Libre");
		UUID retenido = deportista("Retenido");

		Intento b = conElDeportistaRetenido(retenido, () -> servicio.vincular(admin, f1, List.of(libre, retenido), DATOS));

		aTiempoDeTimeout(b);
		assertThat(contar("familia_deportista", escuelaA)).as("todo o nada").isZero();
		assertThat(contarAuditoria("VINCULO_ACTIVADO")).isZero();
		verificarInvariantes();
	}

	@Test
	void sinContencionLasTresOperacionesNoVenAfectadoSuComportamientoNiSuTiempo() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");

		long inicio = System.nanoTime();
		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.vincular(admin, f2, List.of(d), DATOS);
		servicio.cambiarPrincipal(admin, f2, d, DATOS);
		servicio.revocar(admin, f1, d, DATOS);

		assertThat((System.nanoTime() - inicio) / 1_000_000).as("sin esperas").isLessThan(5_000);
		assertThat(principalesActivos(d)).isEqualTo(1);
		verificarInvariantes();
	}

	// ---------- SET LOCAL: valor dentro de la transaccion y ausencia fuera de las de vinculos ----------

	private String lockTimeoutDeLaSesion() {
		return jdbc.sql("SHOW lock_timeout").query(String.class).single();
	}

	@Test
	void setLocalAplicaElPlazoConfiguradoDentroDeLaTransaccionYDesapareceAlTerminarla() {
		String dentro = transaccion.execute(estado -> {
			lockTimeout.aplicar();
			return jdbc.sql("SHOW lock_timeout").query(String.class).single();
		});

		assertThat(dentro).isEqualTo("300ms");
		assertThat(lockTimeoutDeLaSesion()).as("fuera de la transaccion: el valor por defecto de PostgreSQL").isEqualTo("0");
	}

	@Test
	void lasTransaccionesQueNoSonDeVinculosNoTienenLockTimeout() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f1, List.of(d), DATOS);

		// Una transaccion cualquiera (incluida una lectura del propio servicio de vinculos) NO lo tiene, ni siquiera
		// justo despues de una transaccion de vinculos.
		String lectura = transaccion.execute(estado -> {
			servicio.listarDeFamilia(admin, f1);
			return jdbc.sql("SHOW lock_timeout").query(String.class).single();
		});
		String generica = transaccion.execute(estado -> jdbc.sql("SHOW lock_timeout").query(String.class).single());

		assertThat(lectura).isEqualTo("0");
		assertThat(generica).isEqualTo("0");
	}
}
