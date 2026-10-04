package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.banfieldpatin.backend.usuarios.mfa.UsuarioMfa;
import com.banfieldpatin.backend.usuarios.mfa.UsuarioMfaRepository;

import jakarta.persistence.EntityManager;

/**
 * Consultas de UsuarioMfaRepository contra PostgreSQL 17 real: el upsert nativo con ON CONFLICT ... WHERE, las
 * actualizaciones condicionales (confirmar, consumir paso) y el mapeo bytea. Las escrituras corren en su propia
 * transaccion, como en el servicio.
 */
@PruebaDb
class UsuarioMfaRepositoryDbTest extends BaseDbTest {

	@Autowired
	JdbcClient jdbc;
	@Autowired
	EntityManager em;
	@Autowired
	UsuarioMfaRepository mfas;
	@Autowired
	PlatformTransactionManager transacciones;

	private TransactionTemplate tx;
	private DatosDb datos;
	private UUID escuelaId;
	private UUID adminId;

	@BeforeEach
	void preparar() {
		tx = new TransactionTemplate(transacciones);
		datos = new DatosDb(jdbc);
		escuelaId = datos.escuela("mfa-repo-" + UUID.randomUUID());
		adminId = datos.admin(escuelaId, "admin@mfa.example", true);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaId);
	}

	private static byte[] secreto(int semilla) {
		byte[] b = new byte[48];
		for (int i = 0; i < b.length; i++) {
			b[i] = (byte) (semilla + i);
		}
		return b;
	}

	private int enrolar(byte[] cifrado) {
		return tx.execute(s -> mfas.guardarEnrolamiento(adminId, escuelaId, cifrado));
	}

	private int confirmar(long paso) {
		return tx.execute(s -> mfas.confirmar(adminId, paso, Instant.now()));
	}

	private int consumir(long paso) {
		return tx.execute(s -> mfas.consumirPaso(adminId, paso));
	}

	private Long pasoGuardado() {
		return jdbc.sql("SELECT ultimo_paso_usado FROM gestion_patin.usuario_mfa WHERE usuario_id = :u")
				.param("u", adminId).query(Long.class).single();
	}

	private byte[] cifradoGuardado() {
		return jdbc.sql("SELECT secreto_cifrado FROM gestion_patin.usuario_mfa WHERE usuario_id = :u")
				.param("u", adminId).query(byte[].class).single();
	}

	@Test
	void hibernateValidaLaEntidadUsuarioMfaContraV3() {
		var tipos = em.getMetamodel().getEntities().stream().map(e -> e.getJavaType().getSimpleName()).toList();

		assertThat(tipos).contains("UsuarioMfa");
	}

	@Test
	void elEnrolamientoSeGuardaYLaEntidadLeeLosBytesExactos() {
		assertThat(enrolar(secreto(1))).isEqualTo(1);

		UsuarioMfa fila = mfas.findById(adminId).orElseThrow();
		assertThat(fila.getSecretoCifrado()).isEqualTo(secreto(1));
		assertThat(fila.getEscuelaId()).isEqualTo(escuelaId);
		assertThat(fila.estaConfirmado()).isFalse();
		assertThat(fila.getUltimoPasoUsado()).isNull();
		assertThat(fila.getCreadoEn()).isNotNull();
	}

	@Test
	void enrolarDeNuevoAntesDeConfirmarReemplazaElSecretoEnLaMismaFila() {
		enrolar(secreto(1));

		assertThat(enrolar(secreto(100))).isEqualTo(1);

		assertThat(cifradoGuardado()).isEqualTo(secreto(100));
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.usuario_mfa WHERE usuario_id = :u")
				.param("u", adminId).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void unEnrolamientoConfirmadoNoSeReemplazaNiPierdeSuPaso() {
		enrolar(secreto(1));
		confirmar(500L);

		assertThat(enrolar(secreto(100))).isZero();

		assertThat(cifradoGuardado()).isEqualTo(secreto(1));
		assertThat(pasoGuardado()).isEqualTo(500L);
	}

	@Test
	void confirmarSoloAfectaUnaVezYGuardaElPasoDelPrimerCodigo() {
		enrolar(secreto(1));

		assertThat(confirmar(42L)).isEqualTo(1);
		assertThat(confirmar(43L)).isZero();

		assertThat(pasoGuardado()).isEqualTo(42L);
		assertThat(mfas.findById(adminId).orElseThrow().estaConfirmado()).isTrue();
	}

	@Test
	void confirmarSinEnrolamientoNoAfectaNada() {
		assertThat(confirmar(1L)).isZero();
	}

	@Test
	void consumirPasoSoloAceptaPasosEstrictamenteMayoresYSoloConFactorConfirmado() {
		enrolar(secreto(1));
		// Sin confirmar no se consume ningun paso (ademas lo impide el CHECK de la tabla).
		assertThat(consumir(10L)).isZero();
		confirmar(100L);

		assertThat(consumir(100L)).as("igual al ultimo").isZero();
		assertThat(consumir(99L)).as("menor").isZero();
		assertThat(consumir(101L)).as("mayor").isEqualTo(1);
		assertThat(pasoGuardado()).isEqualTo(101L);
		assertThat(consumir(101L)).as("repeticion").isZero();
		assertThat(consumir(150L)).isEqualTo(1);
		assertThat(pasoGuardado()).isEqualTo(150L);
	}

	@Test
	void dosConsumosConcurrentesDelMismoPasoDejanPasarExactamenteUno() throws Exception {
		enrolar(secreto(1));
		confirmar(100L);

		for (int ronda = 0; ronda < 5; ronda++) {
			long paso = 1000L + ronda;
			int hilos = 6;
			ExecutorService pool = Executors.newFixedThreadPool(hilos);
			CountDownLatch salida = new CountDownLatch(1);
			List<Future<Integer>> futuros = new ArrayList<>();
			for (int i = 0; i < hilos; i++) {
				futuros.add(pool.submit(() -> {
					salida.await();
					return consumir(paso);
				}));
			}
			salida.countDown();
			int ganadores = 0;
			for (Future<Integer> f : futuros) {
				ganadores += f.get(30, TimeUnit.SECONDS);
			}
			pool.shutdown();

			assertThat(ganadores).as("ronda %d", ronda).isEqualTo(1);
			assertThat(pasoGuardado()).isEqualTo(paso);
		}
	}

	@Test
	void eliminarBorraLaFilaUnaSolaVez() {
		enrolar(secreto(1));

		assertThat((int) tx.execute(s -> mfas.eliminar(adminId))).isEqualTo(1);
		assertThat((int) tx.execute(s -> mfas.eliminar(adminId))).isZero();
		assertThat(mfas.findById(adminId)).isEmpty();
	}
}
