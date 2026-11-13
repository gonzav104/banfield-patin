package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.familias.portal.FamiliaPortalFichaService;
import com.banfieldpatin.backend.familias.portal.FamiliaPortalService;
import com.banfieldpatin.backend.familias.portal.dto.FichaDeportistaDeFamilia;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/**
 * La ficha de FAMILIA a nivel de servicio contra PostgreSQL real: techo de sentencias, el alcance propio de CADA una de las
 * dos consultas y la semantica real de los cambios concurrentes de vinculos y de la temporada activa bajo
 * {@code REPEATABLE_READ}.
 *
 * <p>
 * El intercalado es determinista y no usa esperas a ciegas: la prueba toma un bloqueo exclusivo de TABLA sobre
 * {@code deportista_temporada} (que solo lee la SEGUNDA consulta), lanza la ficha en otro hilo, espera a que PostgreSQL
 * informe que esa segunda consulta esta bloqueada por la conexion de la prueba (tras haber terminado la primera), confirma el
 * cambio concurrente en otra conexion y recien entonces libera el bloqueo. Asi el cambio queda confirmado EXACTAMENTE entre
 * las dos consultas.
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class FamiliaPortalFichaDbTest extends BaseVinculosDb {

	@Autowired
	FamiliaPortalFichaService ficha;
	@Autowired
	FamiliaPortalService listado;
	@Autowired
	DataSource dataSource;

	private UUID familia;
	private UsuarioAutenticado usuario;
	private UUID t26;
	private UUID t27;
	private UUID deportista;
	private UUID vinculo;

	@BeforeEach
	void sembrar() {
		familia = datos.familia(escuelaA, "Familia ficha", true);
		usuario = new UsuarioAutenticado(UUID.randomUUID(), escuelaA, familia, Rol.FAMILIA);
		UUID t25 = datos.temporada(escuelaA, 2025, false);
		t26 = datos.temporada(escuelaA, 2026, true);
		t27 = datos.temporada(escuelaA, 2027, false);
		deportista = datos.deportista(escuelaA, "33000001", "Ana", "Paz", true);
		vinculo = datos.vinculo(escuelaA, familia, deportista, "ACTIVO", true, adminId);
		datos.datosDeportivos(escuelaA, deportista, t25, "Sub 10", null, "ACTIVO");
		datos.datosDeportivos(escuelaA, deportista, t26, "Sub 12", "007", "ACTIVO");
		datos.datosDeportivos(escuelaA, deportista, t27, "Sub 14", null, "ACTIVO");
	}

	// ---------- techo de sentencias ----------

	@Test
	void laFichaUsaComoMaximoDosSentenciasAunqueElDeportistaTengaMuchasTemporadas() {
		for (int anio = 2020; anio <= 2024; anio++) {
			datos.datosDeportivos(escuelaA, deportista, datos.temporada(escuelaA, anio, false), "Vieja " + anio, null, "ACTIVO");
		}
		FichaDeportistaDeFamilia[] resultado = new FichaDeportistaDeFamilia[1];

		long sentencias = sentencias(() -> resultado[0] = ficha.ficha(usuario, deportista));

		assertThat(sentencias).isLessThanOrEqualTo(2);
		assertThat(resultado[0].temporadaVigente().categoria()).isEqualTo("Sub 12");
	}

	@Test
	void unNotFoundCuestaUnaSolaSentenciaPorqueNuncaBuscaLaTemporada() {
		UUID ajeno = UUID.randomUUID();

		long sentencias = sentencias(() -> catchThrowable(() -> ficha.ficha(usuario, ajeno)));

		assertThat(sentencias).isEqualTo(1);
	}

	// ---------- cada consulta repite el alcance completo de la familia ----------

	@Test
	void laSegundaConsultaPorSiSolaRespetaVinculoFamiliaYEscuelaSinDependerDeLaPrimera() {
		UUID otraFamilia = datos.familia(escuelaA, "Otra", true);
		assertThat(repositorio.temporadaVigenteDelPortal(familia, escuelaA, deportista)).isPresent();

		assertThat(repositorio.temporadaVigenteDelPortal(otraFamilia, escuelaA, deportista)).as("otra familia").isEmpty();
		assertThat(repositorio.temporadaVigenteDelPortal(familia, escuelaB, deportista)).as("otra escuela").isEmpty();
		assertThat(repositorio.temporadaVigenteDelPortal(familia, escuelaA, UUID.randomUUID())).as("inexistente").isEmpty();
		datos.desactivarFamilia(familia);
		assertThat(repositorio.temporadaVigenteDelPortal(familia, escuelaA, deportista)).as("familia inactiva").isEmpty();
		datos.reactivarFamilia(familia);
		for (String estado : new String[] { "REVOCADO", "PENDIENTE", "RECHAZADO" }) {
			datos.estadoVinculo(vinculo, estado, adminId);
			assertThat(repositorio.temporadaVigenteDelPortal(familia, escuelaA, deportista)).as("vinculo " + estado).isEmpty();
		}
		datos.estadoVinculo(vinculo, "ACTIVO", adminId);
		assertThat(repositorio.temporadaVigenteDelPortal(familia, escuelaA, deportista)).isPresent();
	}

	@Test
	void laPrimeraConsultaPorSiSolaRespetaVinculoFamiliaYEscuela() {
		UUID otraFamilia = datos.familia(escuelaA, "Otra", true);
		assertThat(repositorio.fichaBaseDelPortal(familia, escuelaA, deportista)).isPresent();

		assertThat(repositorio.fichaBaseDelPortal(otraFamilia, escuelaA, deportista)).as("otra familia").isEmpty();
		assertThat(repositorio.fichaBaseDelPortal(familia, escuelaB, deportista)).as("otra escuela").isEmpty();
		datos.estadoVinculo(vinculo, "REVOCADO", adminId);
		assertThat(repositorio.fichaBaseDelPortal(familia, escuelaA, deportista)).as("vinculo revocado").isEmpty();
	}

	// ---------- una sola regla de edad en la ficha y el listado ----------

	@Test
	void laFichaYElListadoCalculanLaMismaEdadParaLasMismasFechasIncluidosLosBordes() {
		java.time.LocalDate hoy = java.time.LocalDate.now(com.banfieldpatin.backend.familias.portal.EdadDeportista.ZONA_ESCUELA);
		java.time.LocalDate[] fechas = { java.time.LocalDate.of(2016, 2, 29), java.time.LocalDate.of(2000, 2, 29),
				java.time.LocalDate.of(2012, 2, 28), java.time.LocalDate.of(2012, 3, 1), hoy, hoy.minusDays(1),
				hoy.plusDays(1), hoy.minusYears(120), hoy.minusYears(120).minusDays(1), hoy.minusYears(30).plusDays(1) };
		java.util.List<UUID> ids = new java.util.ArrayList<>();
		int dni = 33100000;
		for (java.time.LocalDate fecha : fechas) {
			UUID d = datos.deportista(escuelaA, String.valueOf(dni++), "Edad", "Zeta" + dni, true);
			datos.vinculo(escuelaA, familia, d, "ACTIVO", false, adminId);
			jdbc.sql("UPDATE gestion_patin.deportista SET fecha_nacimiento = :f WHERE id = :d").param("f", fecha).param("d", d)
					.update();
			ids.add(d);
		}

		var filas = listado.deportistas(usuario, org.springframework.data.domain.PageRequest.of(0, 100)).contenido();

		for (int i = 0; i < ids.size(); i++) {
			UUID id = ids.get(i);
			var fila = filas.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
			assertThat(fila.edad()).as("fecha %s", fechas[i]).isEqualTo(ficha.ficha(usuario, id).edad());
		}
	}

	// ---------- cambios concurrentes ----------

	@Test
	void unaRevocacionConfirmadaAntesDeLaSolicitudDaElNotFoundUniforme() {
		datos.estadoVinculo(vinculo, "REVOCADO", adminId);

		Throwable error = catchThrowable(() -> ficha.ficha(usuario, deportista));

		assertThat(error).isInstanceOfSatisfying(ExcepcionNegocio.class,
				n -> assertThat(n.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO"));
	}

	/**
	 * SEMANTICA REAL (no se promete mas): la instantanea de REPEATABLE_READ se fija en la primera consulta. Una revocacion
	 * confirmada DESPUES de ella no se ve en esta solicitud: la respuesta es la instantanea consistente (deportista Y
	 * temporada, ambos del mismo instante) y NO un 404 ni una mezcla; la solicitud SIGUIENTE ya da 404.
	 */
	@Test
	void unaRevocacionConfirmadaEntreLasDosConsultasNoCambiaLaInstantaneaDeEstaSolicitudPeroLaSiguienteDa404() throws Exception {
		FichaDeportistaDeFamilia respuesta = fichaConCambioEntreLasDosConsultas(
				() -> datos.estadoVinculo(vinculo, "REVOCADO", adminId));

		assertThat(respuesta.nombre()).isEqualTo("Ana");
		assertThat(respuesta.temporadaVigente()).as("la temporada es de la MISMA instantanea, no nula por la revocacion")
				.isNotNull();
		assertThat(respuesta.temporadaVigente().anio()).isEqualTo((short) 2026);
		assertThat(respuesta.temporadaVigente().categoria()).isEqualTo("Sub 12");

		Throwable siguiente = catchThrowable(() -> ficha.ficha(usuario, deportista));
		assertThat(siguiente).isInstanceOfSatisfying(ExcepcionNegocio.class,
				n -> assertThat(n.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO"));
	}

	@Test
	void unaNuevaTemporadaActivaConfirmadaEntreLasDosConsultasNoSeMezclaConLaInstantaneaPeroLaSiguienteLaVe() throws Exception {
		FichaDeportistaDeFamilia respuesta = fichaConCambioEntreLasDosConsultas(() -> datos.activarTemporada(escuelaA, t27));

		assertThat(respuesta.temporadaVigente().anio()).as("la vigente de esta instantanea").isEqualTo((short) 2026);
		assertThat(respuesta.temporadaVigente().categoria()).isEqualTo("Sub 12");
		FichaDeportistaDeFamilia siguiente = ficha.ficha(usuario, deportista);
		assertThat(siguiente.temporadaVigente().anio()).isEqualTo((short) 2027);
		assertThat(siguiente.temporadaVigente().categoria()).isEqualTo("Sub 14");
	}

	@Test
	void sinCambiosConcurrentesElResultadoEsElMismoConOSinBloqueoIntermedio() throws Exception {
		FichaDeportistaDeFamilia intercalada = fichaConCambioEntreLasDosConsultas(() -> {
		});

		assertThat(intercalada).isEqualTo(ficha.ficha(usuario, deportista));
	}

	/**
	 * Ejecuta la ficha en otro hilo, la detiene entre sus dos consultas con un bloqueo de tabla, confirma {@code cambio} en
	 * otra conexion y la deja seguir. Falla (en vez de colgarse) si la segunda consulta no queda bloqueada.
	 */
	private FichaDeportistaDeFamilia fichaConCambioEntreLasDosConsultas(Runnable cambio) throws Exception {
		try (Connection bloqueo = dataSource.getConnection()) {
			bloqueo.setAutoCommit(false);
			long pidDelBloqueo;
			try (Statement st = bloqueo.createStatement()) {
				st.execute("LOCK TABLE gestion_patin.deportista_temporada IN ACCESS EXCLUSIVE MODE");
				try (var rs = st.executeQuery("SELECT pg_backend_pid()")) {
					rs.next();
					pidDelBloqueo = rs.getLong(1);
				}
			}
			Future<FichaDeportistaDeFamilia> futuro = hilos.submit(() -> ficha.ficha(usuario, deportista));
			esperarSegundaConsultaBloqueadaPor(pidDelBloqueo);

			cambio.run();
			bloqueo.commit();
			return futuro.get(20, TimeUnit.SECONDS);
		}
	}

	private void esperarSegundaConsultaBloqueadaPor(long pid) throws InterruptedException {
		long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (System.nanoTime() < limite) {
			Long esperando = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :pid = ANY(pg_blocking_pids(pid))")
					.param("pid", pid).query(Long.class).single();
			if (esperando > 0) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("la segunda consulta de la ficha no quedo bloqueada por el bloqueo de la prueba");
	}
}
