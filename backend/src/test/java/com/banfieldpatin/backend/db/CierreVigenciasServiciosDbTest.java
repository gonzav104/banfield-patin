package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.vinculos.VinculoAdminService;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
class CierreVigenciasServiciosDbTest extends BaseDbTest {
	private static final DatosSolicitud DATOS = new DatosSolicitud(null, "JUnit");

	@Autowired JdbcClient jdbc;
	@Autowired FamiliaAdminService familias;
	@Autowired DeportistaAdminService deportistas;
	@Autowired VinculoAdminService vinculos;
	@Autowired TransactionTemplate transaccion;

	@Test
	void bajaDeFamiliaCierraYAuditaUnaVezConReactivacionPosterior() {
		var a = caso();
		long revisionVinculos = version(a);
		familias.desactivar(a.admin(), a.familia(), 0L, DATOS);
		familias.desactivar(a.admin(), a.familia(), 1L, DATOS);
		assertCierre(a, "FAMILIA_INACTIVA", "FAMILIA_DESACTIVADA");
		assertThat(version(a)).isEqualTo(revisionVinculos);
		assertThat(principal(a)).isTrue();

		familias.activar(a.admin(), a.familia(), 1L, DATOS);
		assertCierre(a, "FAMILIA_INACTIVA", "FAMILIA_DESACTIVADA");
		assertThat(auditorias(a, "FAMILIA_ACTIVADA")).isEqualTo(1);
	}

	@Test
	void bajaDeDeportistaCierraSusVigenciasSinModificarVinculos() {
		var a = caso();
		long revisionVinculos = version(a);
		deportistas.desactivar(a.admin(), a.deportista(), 0L, DATOS);
		deportistas.desactivar(a.admin(), a.deportista(), 1L, DATOS);
		deportistas.activar(a.admin(), a.deportista(), 1L, DATOS);

		assertCierre(a, "DEPORTISTA_INACTIVO", "DEPORTISTA_DESACTIVADO");
		assertThat(version(a)).isEqualTo(revisionVinculos);
		assertThat(principal(a)).isTrue();
	}

	@Test
	void revocacionCierraSoloEseVinculoYAvanzaLaRevisionUnaVez() {
		var a = caso();
		long revision = version(a);
		var datos = new DatosDb(jdbc);
		var otraFamilia = datos.familia(a.admin().escuelaId(), "Otra", true);
		datos.vinculo(a.admin().escuelaId(), otraFamilia, a.deportista(), "ACTIVO", false, a.admin().id());
		var otra = vigencia(a.admin(), otraFamilia, a.deportista());
		vinculos.revocar(a.admin(), a.familia(), a.deportista(), revision, DATOS);
		vinculos.revocar(a.admin(), a.familia(), a.deportista(), revision + 1, DATOS);

		assertCierre(a, "FAMILIA_DEPORTISTA_REVOCADA", "VINCULO_REVOCADO");
		assertThat(version(a)).isEqualTo(revision + 1);
		assertThat(principal(a)).isFalse();
		assertThat(causa(otra)).isNull();
	}

	@Test
	void rollbackDeTransaccionExternaRevierteCierreBajaYAuditoria() {
		var a = caso();
		transaccion.executeWithoutResult(tx -> {
			familias.desactivar(a.admin(), a.familia(), 0L, DATOS);
			assertThat(causa(a.vigencia())).isEqualTo("FAMILIA_INACTIVA");
			tx.setRollbackOnly();
		});

		assertThat(familiaActiva(a)).isTrue();
		assertThat(causa(a.vigencia())).isNull();
		assertThat(auditorias(a, "FAMILIA_DESACTIVADA")).isZero();
		assertThat(auditorias(a, "VIGENCIA_TUTOR_CERRADA")).isZero();
	}

	@Test
	void otraEscuelaYRevisionObsoletaNoCierranVigencias() {
		var a = caso();
		var otra = caso();
		assertThatThrownBy(() -> familias.desactivar(otra.admin(), a.familia(), 0L, DATOS))
				.isInstanceOfSatisfying(ExcepcionNegocio.class,
						ex -> assertThat(ex.getCodigo()).isEqualTo("FAMILIA_NO_ENCONTRADA"));
		assertThatThrownBy(() -> deportistas.desactivar(a.admin(), a.deportista(), 9L, DATOS))
				.isInstanceOfSatisfying(ExcepcionNegocio.class,
						ex -> assertThat(ex.getCodigo()).isEqualTo("EDICION_DESACTUALIZADA"));
		var datos = new DatosDb(jdbc);
		var usuario = datos.usuarioFamilia(a.admin().escuelaId(), a.familia(), "familia@example.invalid");
		var familiar = new UsuarioAutenticado(usuario, a.admin().escuelaId(), a.familia(), Rol.FAMILIA);
		assertThatThrownBy(() -> familias.desactivar(familiar, a.familia(), 0L, DATOS))
				.isInstanceOf(AccessDeniedException.class);
		assertThat(causa(a.vigencia())).isNull();
		assertThat(causa(otra.vigencia())).isNull();
		assertThat(auditorias(a, "VIGENCIA_TUTOR_CERRADA")).isZero();
	}

	@Test
	void reactivacionEfectivaQuedaAisladaPorEscuela() {
		var a = caso();
		var otra = caso();
		familias.desactivar(otra.admin(), otra.familia(), 0L, DATOS);
		assertThat(familiaActiva(otra)).isFalse();
		assertThatThrownBy(() -> familias.activar(a.admin(), otra.familia(), 1L, DATOS))
				.isInstanceOfSatisfying(ExcepcionNegocio.class,
						ex -> assertThat(ex.getCodigo()).isEqualTo("FAMILIA_NO_ENCONTRADA"));

		transaccion.executeWithoutResult(tx -> {
			familias.desactivar(a.admin(), a.familia(), 0L, DATOS);
			familias.activar(otra.admin(), otra.familia(), 1L, DATOS);
		});
		assertThat(familiaActiva(a)).isFalse();
		assertThat(familiaActiva(otra)).isTrue();
		assertCierre(a, "FAMILIA_INACTIVA", "FAMILIA_DESACTIVADA");
		assertCierre(otra, "FAMILIA_INACTIVA", "FAMILIA_DESACTIVADA");
	}

	@Test
	void cierresSolapadosDeFamiliaYDeportistaCierranYAuditanCadaVigenciaUnaSolaVez() throws Exception {
		var a = caso();
		var otra = vigencia(a.admin(), a.familia(), a.deportista());
		try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
			var segundoPid = new CompletableFuture<Integer>();
			Future<?> segundo = transaccion.execute(status -> {
				int primero = pid();
				familias.desactivar(a.admin(), a.familia(), 0L, DATOS);
				Future<?> futuro = hilos.submit(() -> transaccion.executeWithoutResult(tx -> {
					segundoPid.complete(pid());
					deportistas.desactivar(a.admin(), a.deportista(), 0L, DATOS);
				}));
				esperarBloqueo(primero, segundoPid);
				return futuro;
			});
			segundo.get(5, TimeUnit.SECONDS);
		}
		assertThat(causa(a.vigencia())).isEqualTo("FAMILIA_INACTIVA");
		assertThat(causa(otra)).isEqualTo("FAMILIA_INACTIVA");
		assertThat(auditorias(a, "VIGENCIA_TUTOR_CERRADA")).isEqualTo(2);
		assertThat(auditorias(a, "FAMILIA_DESACTIVADA")).isEqualTo(1);
		assertThat(auditorias(a, "DEPORTISTA_DESACTIVADO")).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(ints = { TransactionDefinition.ISOLATION_READ_COMMITTED, TransactionDefinition.ISOLATION_REPEATABLE_READ })
	void altaConcurrenteSeCierraORequiereReintentarLaTransaccionCompleta(int aislamiento) throws Exception {
		var a = caso();
		var txBaja = new TransactionTemplate(transaccion.getTransactionManager());
		txBaja.setIsolationLevel(aislamiento);
		var alta = new AtomicReference<UUID>();
		try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
			var pidBaja = new CompletableFuture<Integer>();
			Future<?> baja = transaccion.execute(tx -> {
				int primero = pid();
				alta.set(vigencia(a.admin(), a.familia(), a.deportista()));
				Future<?> futuro = hilos.submit(() -> txBaja.executeWithoutResult(b -> {
					pidBaja.complete(pid());
					familias.desactivar(a.admin(), a.familia(), 0L, DATOS);
				}));
				esperarBloqueo(primero, pidBaja);
				return futuro;
			});
			if (aislamiento == TransactionDefinition.ISOLATION_REPEATABLE_READ) {
				assertThatThrownBy(() -> baja.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
						.hasCauseInstanceOf(PessimisticLockingFailureException.class);
				assertThat(familiaActiva(a)).isTrue();
				assertThat(causa(a.vigencia())).isNull();
				assertThat(causa(alta.get())).isNull();
				assertThat(auditorias(a, "VIGENCIA_TUTOR_CERRADA")).isZero();
				assertThat(auditorias(a, "FAMILIA_DESACTIVADA")).isZero();
				txBaja.executeWithoutResult(tx -> familias.desactivar(a.admin(), a.familia(), 0L, DATOS));
			} else {
				baja.get(5, TimeUnit.SECONDS);
			}
		}
		assertThat(familiaActiva(a)).isFalse();
		assertThat(causa(a.vigencia())).isEqualTo("FAMILIA_INACTIVA");
		assertThat(causa(alta.get())).isEqualTo("FAMILIA_INACTIVA");
		assertThat(auditorias(a, "VIGENCIA_TUTOR_CERRADA")).isEqualTo(2);
		assertThat(auditorias(a, "FAMILIA_DESACTIVADA")).isEqualTo(1);
	}

	@Test
	void timeoutEnLaFuncionV15NoCambiaLaFamiliaNiAuditaYPermiteReintentar() {
		var a = caso();
		try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
			var pidBaja = new CompletableFuture<Integer>();
			transaccion.executeWithoutResult(tx -> {
				int primero = pid();
				jdbc.sql("SELECT id FROM gestion_patin.familia WHERE id=:id FOR NO KEY UPDATE")
						.param("id", a.familia()).query(UUID.class).single();
				var baja = CompletableFuture.runAsync(() -> transaccion.executeWithoutResult(b -> {
					pidBaja.complete(pid());
					familias.desactivar(a.admin(), a.familia(), 0L, DATOS);
				}), hilos);
				esperarBloqueo(primero, pidBaja);
				assertThatThrownBy(() -> baja.orTimeout(5, TimeUnit.SECONDS).join())
						.isInstanceOf(CompletionException.class).hasCauseInstanceOf(PessimisticLockingFailureException.class);
			});
		}
		assertThat(familiaActiva(a)).isTrue();
		assertThat(causa(a.vigencia())).isNull();
		assertThat(auditorias(a, "VIGENCIA_TUTOR_CERRADA")).isZero();
		assertThat(auditorias(a, "FAMILIA_DESACTIVADA")).isZero();
		familias.desactivar(a.admin(), a.familia(), 0L, DATOS);
		assertCierre(a, "FAMILIA_INACTIVA", "FAMILIA_DESACTIVADA");
	}

	private int pid() {
		return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
	}

	private void esperarBloqueo(int primero, CompletableFuture<Integer> segundo) {
		long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		do {
			if (segundo.isDone() && jdbc.sql("SELECT :a = ANY(pg_blocking_pids(:b))")
					.param("a", primero).param("b", segundo.join()).query(Boolean.class).single()) {
				return;
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
		} while (System.nanoTime() < limite);
		throw new AssertionError("La segunda transacción no esperó el bloqueo de la primera");
	}

	private record Caso(UsuarioAutenticado admin, UUID familia, UUID deportista, UUID vigencia) {}

	private Caso caso() {
		var datos = new DatosDb(jdbc);
		var escuela = datos.escuela("cierre-java-" + UUID.randomUUID());
		var admin = new UsuarioAutenticado(datos.admin(escuela, "admin@example.invalid", true), escuela, null, Rol.ADMIN);
		var familia = datos.familia(escuela, "Familia", true);
		var deportista = datos.deportista(escuela, "12345678", "Nombre", "Apellido");
		datos.vinculo(escuela, familia, deportista, "ACTIVO", true, admin.id());
		return new Caso(admin, familia, deportista, vigencia(admin, familia, deportista));
	}

	private UUID vigencia(UsuarioAutenticado actor, UUID familia, UUID deportista) {
		var tutor = new DatosDb(jdbc).tutor(actor.escuelaId(), familia, "Nombre", "Tutor");
		return jdbc.sql("""
				INSERT INTO gestion_patin.tutor_deportista
				 (escuela_id,familia_id,deportista_id,tutor_id,creado_por,solicitud_id,huella_solicitud)
				VALUES (:e,:f,:d,:t,:a,gen_random_uuid(),repeat('a',64)) RETURNING id
				""").param("e", actor.escuelaId()).param("f", familia).param("d", deportista)
				.param("t", tutor).param("a", actor.id()).query(UUID.class).single();
	}

	private void assertCierre(Caso c, String causa, String evento) {
		assertThat(causa(c.vigencia())).isEqualTo(causa);
		assertThat(auditorias(c, evento)).isEqualTo(1);
		assertThat(auditorias(c, "VIGENCIA_TUTOR_CERRADA")).isEqualTo(1);
	}

	private long auditorias(Caso c, String accion) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id=:e AND usuario_id=:a AND accion=:x")
				.param("e", c.admin().escuelaId()).param("a", c.admin().id()).param("x", accion).query(Long.class).single();
	}

	private String causa(UUID id) {
		return jdbc.sql("SELECT causa_cierre FROM gestion_patin.tutor_deportista WHERE id=:id")
				.param("id", id).query(String.class).optional().orElse(null);
	}

	private boolean familiaActiva(Caso c) {
		return jdbc.sql("SELECT activa FROM gestion_patin.familia WHERE id=:id")
				.param("id", c.familia()).query(Boolean.class).single();
	}

	private long version(Caso c) {
		return jdbc.sql("SELECT version_vinculos FROM gestion_patin.deportista WHERE id=:id")
				.param("id", c.deportista()).query(Long.class).single();
	}

	private boolean principal(Caso c) {
		return jdbc.sql("SELECT es_principal FROM gestion_patin.familia_deportista WHERE familia_id=:f AND deportista_id=:d")
				.param("f", c.familia()).param("d", c.deportista()).query(Boolean.class).single();
	}
}
