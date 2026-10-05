package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.deportistas.Deportista;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.familias.vinculos.EstadoVinculo;
import com.banfieldpatin.backend.familias.vinculos.dto.ResultadoVinculacion;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculacionRespuesta;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Concurrencia y reglas de vinculos contra PostgreSQL real. Tecnica determinista (como en las altas de deportistas): una
 * transaccion A hace su operacion SIN confirmar, B arranca en otro hilo y se queda bloqueada de verdad en el bloqueo de
 * fila del deportista (se sondea {@code pg_stat_activity.wait_event_type = 'Lock'}), y solo entonces A confirma; despues
 * se aserta el resultado de B. Ademas, rondas de hilos libres con una salida sincronizada. Toda excepcion que no sea un
 * error de negocio (un 500, un deadlock, una violacion sin mapear) hace fallar la prueba. Tras cada carrera se verifican
 * los invariantes de la base: a lo sumo un ACTIVO principal por deportista, una fila por (familia, deportista), todo
 * ACTIVO con autorizacion y ningun principal REVOCADO. Pool de 3 conexiones: dos hilos de carrera y uno de muestreo.
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class VinculosConcurrenciaDbTest extends BaseVinculosDb {

	private static final int RONDAS = 20;

	@org.springframework.beans.factory.annotation.Autowired
	DeportistaRepository deportistas;

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

	private List<ILoggingEvent> eventosDeRestriccion() {
		return logs.list.stream().filter(e -> e.getLoggerName().endsWith("RestriccionViolada")).toList();
	}

	private void sinViolacionesDeRestriccion() {
		// El bloqueo de fila evita llegar al indice: ni siquiera el WARN de una carrera traducida debe aparecer.
		assertThat(eventosDeRestriccion()).isEmpty();
		assertThat(logs.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR));
	}

	private static VinculoRespuesta vinculo(Object resultado) {
		assertThat(resultado).isInstanceOf(VinculoRespuesta.class);
		return (VinculoRespuesta) resultado;
	}

	private static ResultadoVinculacion resultadoDe(Object resultado, UUID familia) {
		assertThat(resultado).isInstanceOf(VinculacionRespuesta.class);
		return ((VinculacionRespuesta) resultado).resultados().stream()
				.filter(r -> r.vinculo().familiaId().equals(familia)).findFirst().orElseThrow().resultado();
	}

	private static void assertError(Object resultado, HttpStatus estado, String codigo) {
		assertThat(resultado).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
			assertThat(e.getEstado()).isEqualTo(estado);
			assertThat(e.getCodigo()).isEqualTo(codigo);
		});
	}

	// ---------- un deportista a dos familias a la vez ----------

	@Test
	void dosVinculacionesDelMismoDeportistaAFamiliasDistintasUnaDetrasDeLaOtraAmbasTienenExitoYUnSoloPrincipal()
			throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");

		Par par = bBloqueadaDetrasDeA(() -> servicio.vincular(admin, f1, List.of(d), DATOS),
				() -> servicio.vincular(admin, f2, List.of(d), DATOS));

		assertThat(resultadoDe(par.a(), f1)).isEqualTo(ResultadoVinculacion.CREADO);
		assertThat(resultadoDe(par.b(), f2)).isEqualTo(ResultadoVinculacion.CREADO);
		// A confirmo primero: es el principal; B, que vio la fila de A tras esperar el bloqueo, no lo es.
		assertThat(fila(f1, d).orElseThrow()).containsEntry("es_principal", true).containsEntry("estado", "ACTIVO");
		assertThat(fila(f2, d).orElseThrow()).containsEntry("es_principal", false).containsEntry("estado", "ACTIVO");
		assertThat(principalesActivos(d)).isEqualTo(1);
		assertThat(contarAuditoria("VINCULO_ACTIVADO")).isEqualTo(2);
		verificarInvariantes();
		sinViolacionesDeRestriccion();
	}

	@Test
	void rondasDeHilosLibresVinculandoUnDeportistaADosFamiliasDejanSiempreExactamenteUnPrincipal() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			UUID d = deportista("R" + ronda);

			List<Object> resultados = enParalelo(() -> servicio.vincular(admin, f1, List.of(d), DATOS),
					() -> servicio.vincular(admin, f2, List.of(d), DATOS));

			assertThat(resultados).as("ronda %d", ronda).allMatch(r -> r instanceof VinculacionRespuesta);
			assertThat(filas(d)).as("ronda %d", ronda).hasSize(2).allSatisfy(f -> assertThat(f.get("estado")).isEqualTo("ACTIVO"));
			assertThat(principalesActivos(d)).as("ronda %d", ronda).isEqualTo(1);
		}
		verificarInvariantes();
		sinViolacionesDeRestriccion();
	}

	// ---------- cambio de principal ----------

	private UUID[] deportistaConTresFamilias(UUID d, UUID f1, UUID f2, UUID f3) {
		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.vincular(admin, f2, List.of(d), DATOS);
		servicio.vincular(admin, f3, List.of(d), DATOS);
		return new UUID[] { f1, f2, f3 };
	}

	@Test
	void unCambioDePrincipalDetrasDeOtroSinConfirmarEsperaYAlFinalHayExactamenteUnPrincipalYAmbosDan200() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID f3 = datos.familia(escuelaA, "Tres", true);
		UUID d = deportista("Uno");
		deportistaConTresFamilias(d, f1, f2, f3);
		UUID vinculoF2 = (UUID) fila(f2, d).orElseThrow().get("id");

		Par par = bBloqueadaDetrasDeA(() -> servicio.cambiarPrincipal(admin, f2, d, DATOS),
				() -> servicio.cambiarPrincipal(admin, f3, d, DATOS));

		assertThat(vinculo(par.a()).esPrincipal()).isTrue();
		assertThat(vinculo(par.b()).esPrincipal()).isTrue();
		// Gano el ultimo en confirmar; el anterior (F2) fue bajado por B y no quedo ningun intermedio violado.
		assertThat(fila(f1, d).orElseThrow()).containsEntry("es_principal", false);
		assertThat(fila(f2, d).orElseThrow()).containsEntry("es_principal", false);
		assertThat(fila(f3, d).orElseThrow()).containsEntry("es_principal", true);
		assertThat(principalesActivos(d)).isEqualTo(1);
		List<String> anteriores = jdbc.sql("""
				SELECT detalle->>'anteriorVinculoId' FROM gestion_patin.auditoria
				WHERE escuela_id = :e AND accion = 'VINCULO_PRINCIPAL_CAMBIADO' ORDER BY id
				""").param("e", escuelaA).query(String.class).list();
		assertThat(anteriores).hasSize(2);
		assertThat(anteriores.get(1)).isEqualTo(vinculoF2.toString());
		verificarInvariantes();
		sinViolacionesDeRestriccion();
	}

	@Test
	void rondasDeCambiosDePrincipalConcurrentesMantienenSiempreUnPrincipalConfirmadoYNuncaUnErrorDeRestriccion()
			throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID f3 = datos.familia(escuelaA, "Tres", true);
		UUID d = deportista("Uno");
		deportistaConTresFamilias(d, f1, f2, f3);
		AtomicBoolean muestrear = new AtomicBoolean(true);
		AtomicInteger minimo = new AtomicInteger(Integer.MAX_VALUE);
		AtomicInteger maximo = new AtomicInteger(0);
		Future<?> muestreador = hilos.submit(() -> {
			while (muestrear.get()) {
				int n = (int) principalesActivos(d);
				minimo.accumulateAndGet(n, Math::min);
				maximo.accumulateAndGet(n, Math::max);
			}
		});

		try {
			for (int ronda = 0; ronda < RONDAS; ronda++) {
				// Rondas pares: dos destinos distintos; impares: el MISMO destino (uno cambia y el otro es idempotente).
				UUID destinoB = ronda % 2 == 0 ? f3 : f2;
				List<Object> resultados = enParalelo(() -> servicio.cambiarPrincipal(admin, f2, d, DATOS),
						() -> servicio.cambiarPrincipal(admin, destinoB, d, DATOS));

				assertThat(resultados).as("ronda %d", ronda).allMatch(r -> r instanceof VinculoRespuesta);
				assertThat(principalesActivos(d)).as("ronda %d", ronda).isEqualTo(1);
				assertThat(filas(d)).hasSize(3).allSatisfy(f -> assertThat(f.get("estado")).isEqualTo("ACTIVO"));
			}
		} finally {
			muestrear.set(false);
			muestreador.get(15, TimeUnit.SECONDS);
		}

		// En ningun momento observable (estado confirmado) hubo cero ni dos principales.
		assertThat(minimo.get()).isEqualTo(1);
		assertThat(maximo.get()).isEqualTo(1);
		verificarInvariantes();
		sinViolacionesDeRestriccion();
	}

	@Test
	void cambiarElPrincipalEntreVinculosActivosDeFamiliasActivasBajaAlAnteriorYSubeAlElegidoSinErrorDeRestriccion() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.vincular(admin, f2, List.of(d), DATOS);
		UUID vinculoF1 = (UUID) fila(f1, d).orElseThrow().get("id");

		VinculoRespuesta r = servicio.cambiarPrincipal(admin, f2, d, DATOS);

		assertThat(r.esPrincipal()).isTrue();
		assertThat(fila(f1, d).orElseThrow()).containsEntry("es_principal", false).containsEntry("estado", "ACTIVO");
		assertThat(fila(f2, d).orElseThrow()).containsEntry("es_principal", true).containsEntry("estado", "ACTIVO");
		assertThat(jdbc.sql("SELECT detalle->>'anteriorVinculoId' FROM gestion_patin.auditoria WHERE accion = 'VINCULO_PRINCIPAL_CAMBIADO' AND escuela_id = :e")
				.param("e", escuelaA).query(String.class).single()).isEqualTo(vinculoF1.toString());
		// Repetir es idempotente: 200 sin escribir ni auditar.
		assertThat(servicio.cambiarPrincipal(admin, f2, d, DATOS).esPrincipal()).isTrue();
		assertThat(contarAuditoria("VINCULO_PRINCIPAL_CAMBIADO")).isEqualTo(1);
		verificarInvariantes();
	}

	@Test
	void elIndiceUnicoDePrincipalEsInmediatoPorEsoElOrdenBajarYLuegoSubirImportaSubirPrimeroViolaria() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.vincular(admin, f2, List.of(d), DATOS);

		// Control negativo: promover ANTES de demotar viola uq_fd_principal_activo dentro de la misma transaccion.
		assertThatThrownBy(() -> transaccion.execute(estado -> {
			jdbc.sql("UPDATE gestion_patin.familia_deportista SET es_principal = true WHERE familia_id = :f AND deportista_id = :d")
					.param("f", f2).param("d", d).update();
			return null;
		})).hasMessageContaining("uq_fd_principal_activo");

		// El servicio hace el orden correcto y no falla.
		servicio.cambiarPrincipal(admin, f2, d, DATOS);
		assertThat(principalesActivos(d)).isEqualTo(1);
	}

	@Test
	void cambiarElPrincipalConLaFamiliaInactivaDa409FamiliaInactivaSinCambiosNiAuditoria() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.vincular(admin, f2, List.of(d), DATOS);
		datos.desactivarFamilia(f2);
		long auditorias = contar("auditoria", escuelaA);

		assertThatThrownBy(() -> servicio.cambiarPrincipal(admin, f2, d, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "FAMILIA_INACTIVA"));

		assertThat(fila(f1, d).orElseThrow()).containsEntry("es_principal", true);
		assertThat(fila(f2, d).orElseThrow()).containsEntry("es_principal", false);
		assertThat(contar("auditoria", escuelaA)).isEqualTo(auditorias);
		// La familia se comprueba primero, aun con un vinculo no ACTIVO.
		servicio.revocar(admin, f2, d, DATOS);
		assertThatThrownBy(() -> servicio.cambiarPrincipal(admin, f2, d, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "FAMILIA_INACTIVA"));
		// Al reactivar la familia el mismo pedido ya recibe su respuesta de vinculo (REVOCADO -> VINCULO_NO_ACTIVO).
		datos.reactivarFamilia(f2);
		assertThatThrownBy(() -> servicio.cambiarPrincipal(admin, f2, d, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "VINCULO_NO_ACTIVO"));
	}

	@Test
	void elPrincipalActualDeUnaFamiliaInactivaSiSeBajaCuandoSePromueveOtraFamiliaActiva() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.vincular(admin, f2, List.of(d), DATOS);
		datos.desactivarFamilia(f1); // la familia del principal actual esta inactiva

		VinculoRespuesta r = servicio.cambiarPrincipal(admin, f2, d, DATOS);

		assertThat(r.esPrincipal()).isTrue();
		assertThat(fila(f1, d).orElseThrow()).containsEntry("es_principal", false).containsEntry("estado", "ACTIVO");
		assertThat(principalesActivos(d)).isEqualTo(1);
		verificarInvariantes();
	}

	@Test
	void cambiarElPrincipalHaciaUnVinculoNoActivoDa409VinculoNoActivoYSinVinculoDa404() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID fRevocada = datos.familia(escuelaA, "Revocada", true);
		UUID fPendiente = datos.familia(escuelaA, "Pendiente", true);
		UUID fRechazada = datos.familia(escuelaA, "Rechazada", true);
		UUID fSin = datos.familia(escuelaA, "Sin vinculo", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.vincular(admin, fRevocada, List.of(d), DATOS);
		servicio.revocar(admin, fRevocada, d, DATOS);
		datos.vinculo(escuelaA, fPendiente, d, "PENDIENTE", false, null);
		datos.vinculo(escuelaA, fRechazada, d, "RECHAZADO", false, null);
		long auditorias = contar("auditoria", escuelaA);

		for (UUID f : List.of(fRevocada, fPendiente, fRechazada)) {
			assertThatThrownBy(() -> servicio.cambiarPrincipal(admin, f, d, DATOS))
					.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "VINCULO_NO_ACTIVO"));
		}
		assertThatThrownBy(() -> servicio.cambiarPrincipal(admin, fSin, d, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "VINCULO_NO_ENCONTRADO"));

		assertThat(fila(f1, d).orElseThrow()).containsEntry("es_principal", true);
		assertThat(contar("auditoria", escuelaA)).isEqualTo(auditorias);
		verificarInvariantes();
	}

	// ---------- vincular y revocar ----------

	@Test
	void revocarElPrincipalDejaAlOtroVinculoNoPrincipalYUnVinculoNuevoSeHacePrincipal() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID f3 = datos.familia(escuelaA, "Tres", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.vincular(admin, f2, List.of(d), DATOS);

		VinculoRespuesta revocado = servicio.revocar(admin, f1, d, DATOS);

		assertThat(revocado.estado()).isEqualTo(EstadoVinculo.REVOCADO);
		assertThat(revocado.esPrincipal()).isFalse();
		// Sin promocion automatica: el otro vinculo sigue ACTIVO y no principal.
		assertThat(fila(f2, d).orElseThrow()).containsEntry("estado", "ACTIVO").containsEntry("es_principal", false);
		assertThat(principalesActivos(d)).isZero();
		assertThat(jdbc.sql("SELECT detalle->>'eraPrincipal' FROM gestion_patin.auditoria WHERE accion = 'VINCULO_REVOCADO' AND escuela_id = :e")
				.param("e", escuelaA).query(String.class).single()).isEqualTo("true");

		// Repetir la revocacion es idempotente (200, una sola auditoria).
		assertThat(servicio.revocar(admin, f1, d, DATOS).estado()).isEqualTo(EstadoVinculo.REVOCADO);
		assertThat(contarAuditoria("VINCULO_REVOCADO")).isEqualTo(1);

		// Un vinculo nuevo a una tercera familia se hace principal porque no hay ningun ACTIVO principal.
		assertThat(servicio.vincular(admin, f3, List.of(d), DATOS).resultados().get(0).vinculo().esPrincipal()).isTrue();
		verificarInvariantes();
	}

	@Test
	void revocarEstaPermitidoConLaFamiliaInactivaYUnPendienteORechazadoDa409() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID fPendiente = datos.familia(escuelaA, "Pendiente", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f1, List.of(d), DATOS);
		datos.vinculo(escuelaA, fPendiente, d, "PENDIENTE", false, null);
		datos.desactivarFamilia(f1);

		assertThat(servicio.revocar(admin, f1, d, DATOS).estado()).isEqualTo(EstadoVinculo.REVOCADO);
		assertThatThrownBy(() -> servicio.revocar(admin, fPendiente, d, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "VINCULO_NO_ACTIVO"));
		assertThatThrownBy(() -> servicio.revocar(admin, UUID.randomUUID(), d, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "VINCULO_NO_ENCONTRADO"));
	}

	@Test
	void vincularAUnaFamiliaInactivaDa409NoEscribeNadaYLaFilaRevocadaSigueRevocada() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");
		UUID d2 = deportista("Dos");
		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.revocar(admin, f1, d, DATOS);
		datos.desactivarFamilia(f1);
		long auditorias = contar("auditoria", escuelaA);

		assertThatThrownBy(() -> servicio.vincular(admin, f1, List.of(d, d2), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "FAMILIA_INACTIVA"));

		assertThat(fila(f1, d).orElseThrow()).containsEntry("estado", "REVOCADO").containsEntry("es_principal", false);
		assertThat(fila(f1, d2)).isEmpty();
		assertThat(contar("auditoria", escuelaA)).isEqualTo(auditorias);
		// Incluso un lote que seria todo SIN_CAMBIOS se rechaza con la familia inactiva (la familia se comprueba primero).
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		servicio.vincular(admin, f2, List.of(d), DATOS);
		datos.desactivarFamilia(f2);
		assertThatThrownBy(() -> servicio.vincular(admin, f2, List.of(d), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "FAMILIA_INACTIVA"));
	}

	@Test
	void unLoteConUnDeportistaAjenoOInactivoNoPersisteNingunVinculoNiAuditoria() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID propio = deportista("Propio");
		UUID ajeno = deportista(escuelaB, "Ajeno", true);
		UUID inactivo = deportista(escuelaA, "Inactivo", false);

		assertThatThrownBy(() -> servicio.vincular(admin, f1, List.of(propio, ajeno), DATOS))
				.satisfies(e -> {
					assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO");
					assertThat(((ExcepcionNegocio) e).getDetalles()).extracting(d -> d.campo()).containsExactly("deportistaIds[1]");
				});
		assertThatThrownBy(() -> servicio.vincular(admin, f1, List.of(propio, inactivo), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "DEPORTISTA_INACTIVO"));

		assertThat(contar("familia_deportista", escuelaA)).isZero();
		assertThat(contar("familia_deportista", escuelaB)).isZero();
		assertThat(contar("auditoria", escuelaA)).isZero();
	}

	@Test
	void unaVinculacionConUnaFamiliaYUnDeportistaDeOtraEscuelaDan404IdenticoAUnIdAleatorio() {
		UUID familiaB = datos.familia(escuelaB, "De B", true);
		UUID d = deportista("Uno");

		Throwable ajena = org.assertj.core.api.Assertions.catchThrowable(() -> servicio.vincular(admin, familiaB, List.of(d), DATOS));
		Throwable aleatoria = org.assertj.core.api.Assertions
				.catchThrowable(() -> servicio.vincular(admin, UUID.randomUUID(), List.of(d), DATOS));

		assertThat(ajena).isInstanceOf(ExcepcionNegocio.class).hasMessage(aleatoria.getMessage());
		assertThat(((ExcepcionNegocio) ajena).getCodigo()).isEqualTo(((ExcepcionNegocio) aleatoria).getCodigo())
				.isEqualTo("FAMILIA_NO_ENCONTRADA");
	}

	// ---------- el mismo vinculo, vincular contra revocar ----------

	@Test
	void elMismoVinculoDosVecesUnaDetrasDeLaOtraDaUnCreadoYUnSinCambiosConUnaSolaFila() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");

		Par par = bBloqueadaDetrasDeA(() -> servicio.vincular(admin, f1, List.of(d), DATOS),
				() -> servicio.vincular(admin, f1, List.of(d), DATOS));

		assertThat(resultadoDe(par.a(), f1)).isEqualTo(ResultadoVinculacion.CREADO);
		assertThat(resultadoDe(par.b(), f1)).isEqualTo(ResultadoVinculacion.SIN_CAMBIOS);
		assertThat(filas(d)).hasSize(1);
		assertThat(contarAuditoria("VINCULO_ACTIVADO")).isEqualTo(1);
		verificarInvariantes();
		sinViolacionesDeRestriccion();
	}

	@Test
	void rondasDelMismoVinculoALaVezDanSiempreUnCreadoYUnSinCambiosYUnaFila() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			UUID d = deportista("R" + ronda);

			List<Object> resultados = enParalelo(() -> servicio.vincular(admin, f1, List.of(d), DATOS),
					() -> servicio.vincular(admin, f1, List.of(d), DATOS));

			assertThat(resultados.stream().map(r -> resultadoDe(r, f1))).as("ronda %d", ronda)
					.containsExactlyInAnyOrder(ResultadoVinculacion.CREADO, ResultadoVinculacion.SIN_CAMBIOS);
			assertThat(filas(d)).as("ronda %d", ronda).hasSize(1);
		}
		assertThat(contarAuditoria("VINCULO_ACTIVADO")).isEqualTo(RONDAS);
		verificarInvariantes();
		sinViolacionesDeRestriccion();
	}

	@Test
	void revocarDetrasDeUnaVinculacionSinConfirmarYVincularDetrasDeUnaRevocacionTerminanEnEstadosValidos() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.revocar(admin, f1, d, DATOS);

		// A vuelve a vincular sin confirmar; B revoca detras: ve el ACTIVO de A y lo revoca. Final REVOCADO.
		Par vinculaYRevoca = bBloqueadaDetrasDeA(() -> servicio.vincular(admin, f1, List.of(d), DATOS),
				() -> servicio.revocar(admin, f1, d, DATOS));
		assertThat(resultadoDe(vinculaYRevoca.a(), f1)).isEqualTo(ResultadoVinculacion.REACTIVADO);
		assertThat(vinculo(vinculaYRevoca.b()).estado()).isEqualTo(EstadoVinculo.REVOCADO);
		assertThat(fila(f1, d).orElseThrow()).containsEntry("estado", "REVOCADO").containsEntry("es_principal", false);

		// A revoca (sobre un ACTIVO) sin confirmar; B vincula detras: ve el REVOCADO de A y lo reutiliza. Final ACTIVO principal.
		servicio.vincular(admin, f1, List.of(d), DATOS);
		Par revocaYVincula = bBloqueadaDetrasDeA(() -> servicio.revocar(admin, f1, d, DATOS),
				() -> servicio.vincular(admin, f1, List.of(d), DATOS));
		assertThat(vinculo(revocaYVincula.a()).estado()).isEqualTo(EstadoVinculo.REVOCADO);
		assertThat(resultadoDe(revocaYVincula.b(), f1)).isEqualTo(ResultadoVinculacion.REACTIVADO);
		assertThat(fila(f1, d).orElseThrow()).containsEntry("estado", "ACTIVO").containsEntry("es_principal", true);
		assertThat(filas(d)).hasSize(1);
		verificarInvariantes();
		sinViolacionesDeRestriccion();
	}

	@Test
	void rondasLibresDeVincularContraRevocarDejanUnaFilaYUnEstadoCoherenteConLosResultados() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			UUID d = deportista("R" + ronda);
			servicio.vincular(admin, f1, List.of(d), DATOS);

			List<Object> resultados = enParalelo(() -> servicio.vincular(admin, f1, List.of(d), DATOS),
					() -> servicio.revocar(admin, f1, d, DATOS));

			ResultadoVinculacion vincular = resultadoDe(resultados.get(0), f1);
			assertThat(vinculo(resultados.get(1)).estado()).isEqualTo(EstadoVinculo.REVOCADO);
			assertThat(filas(d)).as("ronda %d", ronda).hasSize(1);
			if (vincular == ResultadoVinculacion.SIN_CAMBIOS) { // vincular gano el bloqueo: despues se revoco
				assertThat(fila(f1, d).orElseThrow()).as("ronda %d", ronda).containsEntry("estado", "REVOCADO");
			} else { // revocar gano: vincular reutilizo la fila
				assertThat(vincular).isEqualTo(ResultadoVinculacion.REACTIVADO);
				assertThat(fila(f1, d).orElseThrow()).as("ronda %d", ronda).containsEntry("estado", "ACTIVO")
						.containsEntry("es_principal", true);
			}
		}
		verificarInvariantes();
		sinViolacionesDeRestriccion();
	}

	// ---------- lotes con ids superpuestos en orden inverso ----------

	@Test
	void elBloqueoDeLosDeportistasDevuelveLasFilasEnOrdenDeIdSeaCualSeaElOrdenPedido() {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			ids.add(deportista("O" + i));
		}
		List<UUID> inverso = new ArrayList<>(ids);
		java.util.Collections.reverse(inverso);

		List<String> bloqueados = transaccion.execute(s -> deportistas.bloquearParaVincular(escuelaA, inverso).stream()
				.map(Deportista::getId).map(UUID::toString).toList());

		// El orden de PostgreSQL para uuid es el de sus bytes (texto hexadecimal), no el de java.util.UUID.compareTo.
		assertThat(bloqueados).hasSize(6).isSorted();
	}

	@Test
	void lotesConIdsSuperpuestosEnOrdenInversoDesdeDosHilosNoSeBloqueanEntreSiYAmbosTerminanValidos() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			List<UUID> ids = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				ids.add(deportista("L" + ronda + "x" + i));
			}
			List<UUID> inverso = new ArrayList<>(ids);
			java.util.Collections.reverse(inverso);

			List<Object> resultados = enParalelo(() -> servicio.vincular(admin, f1, ids, DATOS),
					() -> servicio.vincular(admin, f2, inverso, DATOS));

			// Un deadlock (40P01) o un bloqueo mal ordenado habria hecho fallar enParalelo con una excepcion no de negocio.
			assertThat(resultados).as("ronda %d", ronda).allMatch(r -> r instanceof VinculacionRespuesta);
			for (UUID d : ids) {
				assertThat(filas(d)).as("ronda %d", ronda).hasSize(2);
				assertThat(principalesActivos(d)).as("ronda %d", ronda).isEqualTo(1);
			}
		}
		verificarInvariantes();
		sinViolacionesDeRestriccion();
	}

	// ---------- desactivacion concurrente de la familia (benigna) ----------

	/**
	 * La fila de la familia NO se bloquea (decision de diseno): si un ADMIN desactiva la familia mientras otra transaccion
	 * esta vinculando (A aun sin confirmar), la desactivacion no espera (UPDATE de una columna no clave es compatible con el
	 * FOR KEY SHARE de la FK) y el resultado es un vinculo ACTIVO a una familia inactiva: un estado valido que no concede
	 * nada, es visible para el ADMIN y se puede revocar; cambiar su principal da 409 FAMILIA_INACTIVA.
	 */
	@Test
	void desactivarLaFamiliaMientrasSeVinculaEsBenignoYDejaUnEstadoValidoVisibleYRevocable() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");
		CountDownLatch aHizo = new CountDownLatch(1);
		CountDownLatch confirmar = new CountDownLatch(1);
		Future<Object> vincula = hilos.submit(() -> transaccion.execute(estado -> {
			Object r = servicio.vincular(admin, f1, List.of(d), DATOS);
			aHizo.countDown();
			esperar(confirmar);
			return r;
		}));
		assertThat(aHizo.await(15, TimeUnit.SECONDS)).isTrue();

		datos.desactivarFamilia(f1); // no espera: la fila de la familia no esta bloqueada por la vinculacion
		confirmar.countDown();
		try {
			vincula.get(15, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw new AssertionError("la vinculacion no debia fallar", e);
		}

		assertThat(fila(f1, d).orElseThrow()).containsEntry("estado", "ACTIVO");
		assertThat(servicio.listarDeFamilia(admin, f1)).singleElement().satisfies(v -> assertThat(v.estado()).isEqualTo(EstadoVinculo.ACTIVO));
		assertThatThrownBy(() -> servicio.cambiarPrincipal(admin, f1, d, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "FAMILIA_INACTIVA"));
		assertThat(servicio.revocar(admin, f1, d, DATOS).estado()).isEqualTo(EstadoVinculo.REVOCADO);
		verificarInvariantes();
	}
}
