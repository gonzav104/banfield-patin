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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.temporadas.DeportistaTemporadaAdminService;
import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.banfieldpatin.backend.temporadas.TemporadaAdminService;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaActualizacion;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaRespuesta;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaSolicitud;
import com.banfieldpatin.backend.temporadas.dto.TemporadaRespuesta;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Concurrencia de los datos deportivos contra PostgreSQL real. Tecnica determinista (como en vinculos y temporadas): una
 * transaccion A hace su operacion SIN confirmar, B arranca en otro hilo y queda bloqueada de verdad (se sondea
 * {@code pg_stat_activity.wait_event_type = 'Lock'}) y solo entonces A confirma. Ademas, rondas de hilos libres con una salida
 * sincronizada. Tambien se prueba, contra la base, que el bloqueo de la fila es {@code FOR NO KEY UPDATE}, que serializa las
 * escrituras SOBRE ESA FILA y no frena otras filas ni la activacion de una temporada, y que un PUT con datos viejos pisa un
 * cambio posterior (limitacion aceptada y documentada: no hay version por fila).
 */
@PruebaDb
@Import(ConfigTemporadasDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=5",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class DeportistaTemporadaConcurrenciaDbTest extends BaseTemporadasDb {

	private static final int RONDAS = 15;
	private static final long SIN_ESPERA_SEGUNDOS = 5;

	@Autowired
	DeportistaTemporadaAdminService dts;
	@Autowired
	TemporadaAdminService temporadasAdmin;

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

	private void sinErrores() {
		assertThat(logs.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.ERROR));
	}

	private DeportistaTemporadaRespuesta alta(UUID deportista, UUID temporada, String numero) {
		return dts.crear(admin, deportista, new DeportistaTemporadaSolicitud(temporada, "Sub 12", null, null, numero, null), DATOS);
	}

	private DeportistaTemporadaActualizacion cambio(String categoria, String numero) {
		return new DeportistaTemporadaActualizacion(categoria, null, null, numero, EstadoDeportivo.ACTIVO);
	}

	private static void assertError(Object resultado, HttpStatus estado, String codigo) {
		assertThat(resultado).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
			assertThat(e.getEstado()).isEqualTo(estado);
			assertThat(e.getCodigo()).isEqualTo(codigo);
		});
	}

	private long filas(UUID temporada) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.deportista_temporada WHERE temporada_id = :t").param("t", temporada)
				.query(Long.class).single();
	}

	// ---------- unicidades resueltas por la base ----------

	@Test
	void dosAltasDelMismoParUnaDetrasDeLaOtraLaSegundaDa409PorLaRestriccionYNoQuedaUnaSegundaFila() throws Exception {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);

		Retencion a = new Retencion(() -> alta(d, t, null));
		Future<Object> b = enOtroHilo(() -> alta(d, t, null));
		try {
			assertBloqueada(b);
		} finally {
			a.close();
		}

		assertThat(a.confirmar()).isInstanceOf(DeportistaTemporadaRespuesta.class);
		assertError(b.get(15, TimeUnit.SECONDS), HttpStatus.CONFLICT, "DEPORTISTA_TEMPORADA_DUPLICADA");
		assertThat(filas(t)).isEqualTo(1);
		assertThat(contarAuditoria(escuelaA, "DEPORTISTA_TEMPORADA_CREADA")).isEqualTo(1);
	}

	@Test
	void dosAltasConElMismoNumeroEnLaMismaTemporadaUnaDetrasDeLaOtraLaSegundaDa409() throws Exception {
		UUID d1 = deportista(escuelaA, "Ana", "Perez", true);
		UUID d2 = deportista(escuelaA, "Eva", "Gomez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);

		Retencion a = new Retencion(() -> alta(d1, t, "007"));
		Future<Object> b = enOtroHilo(() -> alta(d2, t, "007"));
		try {
			assertBloqueada(b);
		} finally {
			a.close();
		}

		a.confirmar();
		assertError(b.get(15, TimeUnit.SECONDS), HttpStatus.CONFLICT, "NUMERO_CORREDOR_DUPLICADO");
		assertThat(filas(t)).isEqualTo(1);
	}

	@Test
	void rondasDeHilosLibresConElMismoParODistintoDeportistaYMismoNumeroDejanSiempreUnaSolaFila() throws Exception {
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			UUID t = datos.temporada(escuelaA, 2030 + ronda * 2, false);
			UUID tNumero = datos.temporada(escuelaA, 2031 + ronda * 2, false);
			UUID d = deportista(escuelaA, "Ana" + ronda, "Perez", true);
			UUID e1 = deportista(escuelaA, "Eva" + ronda, "Gomez", true);
			UUID e2 = deportista(escuelaA, "Luz" + ronda, "Diaz", true);

			List<Object> mismoPar = enParalelo(() -> alta(d, t, null), () -> alta(d, t, null));
			List<Object> mismoNumero = enParalelo(() -> alta(e1, tNumero, "9"), () -> alta(e2, tNumero, "9"));

			assertThat(mismoPar).as("mismo par, ronda " + ronda).filteredOn(DeportistaTemporadaRespuesta.class::isInstance)
					.hasSize(1);
			assertThat(mismoPar).filteredOn(ExcepcionNegocio.class::isInstance).hasSize(1).allSatisfy(
					r -> assertError(r, HttpStatus.CONFLICT, "DEPORTISTA_TEMPORADA_DUPLICADA"));
			assertThat(mismoNumero).as("mismo numero, ronda " + ronda).filteredOn(DeportistaTemporadaRespuesta.class::isInstance)
					.hasSize(1);
			assertThat(mismoNumero).filteredOn(ExcepcionNegocio.class::isInstance).hasSize(1).allSatisfy(
					r -> assertError(r, HttpStatus.CONFLICT, "NUMERO_CORREDOR_DUPLICADO"));
			assertThat(filas(t)).isEqualTo(1);
			assertThat(filas(tNumero)).isEqualTo(1);
		}
		sinErrores();
	}

	// ---------- edicion concurrente de la misma fila ----------

	@Test
	void dosEdicionesDeLaMismaFilaSeSerializanYLaSegundaCalculaSusCambiosContraLoYaConfirmado() throws Exception {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		alta(d, t, null);

		Retencion a = new Retencion(() -> dts.actualizar(admin, d, t, cambio("Sub 14", null), DATOS));
		Future<Object> b = enOtroHilo(() -> dts.actualizar(admin, d, t, cambio("Sub 14", null), DATOS));
		String sentencia;
		try {
			sentencia = assertBloqueada(b);
		} finally {
			a.close();
		}

		assertThat(((DeportistaTemporadaRespuesta) a.confirmar()).categoria()).isEqualTo("Sub 14");
		assertThat(((DeportistaTemporadaRespuesta) b.get(15, TimeUnit.SECONDS)).categoria()).isEqualTo("Sub 14");
		// SQL efectivo que recibio el servidor: bloqueo de ESA fila con FOR NO KEY UPDATE, no FOR UPDATE.
		assertThat(sentencia.toLowerCase()).contains("gestion_patin.deportista_temporada").contains("for no key update");
		// B releyo la fila con el bloqueo tomado: ya estaba en "Sub 14", no hubo cambio real y no hay segundo evento.
		assertThat(contarAuditoria(escuelaA, "DEPORTISTA_TEMPORADA_ACTUALIZADA")).isEqualTo(1);
		sinErrores();
	}

	@Test
	void rondasDeEdicionesLibresDejanUnaFilaCoherenteYTantosEventosComoCambiosReales() throws Exception {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		alta(d, t, null);
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			String uno = "Cat " + ronda + "-1";
			String otro = "Cat " + ronda + "-2";

			List<Object> resultados = enParalelo(() -> dts.actualizar(admin, d, t, cambio(uno, null), DATOS),
					() -> dts.actualizar(admin, d, t, cambio(otro, null), DATOS));

			assertThat(resultados).allSatisfy(r -> assertThat(r).isInstanceOf(DeportistaTemporadaRespuesta.class));
			assertThat(dts.obtener(admin, d, t).categoria()).isIn(uno, otro);
		}
		// Cada ronda cambia la categoria dos veces: ambas ediciones son cambios reales (la segunda difiere de la primera).
		assertThat(contarAuditoria(escuelaA, "DEPORTISTA_TEMPORADA_ACTUALIZADA")).isEqualTo(RONDAS * 2L);
		sinErrores();
	}

	/**
	 * LIMITACION ACEPTADA (D6): el bloqueo serializa las escrituras pero NO evita que un PUT armado con datos viejos pise un
	 * cambio posterior. No hay columna de version y agregarla exigiria una migracion: gana la ultima escritura.
	 */
	@Test
	void unPutArmadoConDatosViejosPisaElCambioPosteriorPorqueGanaLaUltimaEscritura() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		alta(d, t, "7");

		// Un administrador cambia el numero a "9"...
		dts.actualizar(admin, d, t, cambio("Sub 12", "9"), DATOS);
		// ...y otro, con la pantalla vieja (numero "7"), solo quiere cambiar la categoria y reenvia el numero que veia.
		dts.actualizar(admin, d, t, cambio("Sub 14", "7"), DATOS);

		DeportistaTemporadaRespuesta final_ = dts.obtener(admin, d, t);
		assertThat(final_.categoria()).isEqualTo("Sub 14");
		assertThat(final_.numeroCorredor()).as("el cambio a 9 se perdio: ultima escritura gana").isEqualTo("7");
	}

	// ---------- que el bloqueo sea solo el de esa fila ----------

	@Test
	void unaEdicionRetenidaNoFrenaLaEdicionDeOtraFilaDeLaMismaTemporada() throws Exception {
		UUID d1 = deportista(escuelaA, "Ana", "Perez", true);
		UUID d2 = deportista(escuelaA, "Eva", "Gomez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		alta(d1, t, null);
		alta(d2, t, null);

		try (Retencion a = new Retencion(() -> dts.actualizar(admin, d1, t, cambio("Sub 14", null), DATOS))) {
			Object b = enOtroHilo(() -> dts.actualizar(admin, d2, t, cambio("Sub 16", null), DATOS)).get(SIN_ESPERA_SEGUNDOS,
					TimeUnit.SECONDS);

			assertThat(((DeportistaTemporadaRespuesta) b).categoria()).isEqualTo("Sub 16");
			a.confirmar();
		}
		assertThat(dts.obtener(admin, d1, t).categoria()).isEqualTo("Sub 14");
	}

	@Test
	void unaActivacionDeTemporadaRetenidaNoFrenaElAltaNiLaEdicionDeDatosDeportivos() throws Exception {
		UUID d1 = deportista(escuelaA, "Ana", "Perez", true);
		UUID d2 = deportista(escuelaA, "Eva", "Gomez", true);
		UUID aActivar = datos.temporada(escuelaA, 2027, false);
		UUID otra = datos.temporada(escuelaA, 2028, false);
		alta(d1, otra, null);

		try (Retencion a = new Retencion(() -> temporadasAdmin.activar(admin, aActivar, DATOS))) {
			Future<Object> altaNueva = enOtroHilo(() -> alta(d2, otra, "5"));
			Future<Object> edicion = enOtroHilo(() -> dts.actualizar(admin, d1, otra, cambio("Sub 16", null), DATOS));

			assertThat(altaNueva.get(SIN_ESPERA_SEGUNDOS, TimeUnit.SECONDS)).isInstanceOf(DeportistaTemporadaRespuesta.class);
			assertThat(edicion.get(SIN_ESPERA_SEGUNDOS, TimeUnit.SECONDS)).isInstanceOf(DeportistaTemporadaRespuesta.class);
			assertThat(activa(aActivar)).as("la activacion sigue sin confirmar").isFalse();
			assertThat(a.confirmar()).isInstanceOf(TemporadaRespuesta.class);
		}
		assertThat(activasDe(escuelaA)).containsExactly(aActivar);
	}
}
