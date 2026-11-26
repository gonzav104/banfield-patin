package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** V16 con el login runtime real: permisos, idempotencia, aislamiento, historial, FKs de actor y concurrencia. */
@Tag("db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V16EscriturasVigenciasDbTest {
	private static final String OWNER = "banfield_schema_owner";
	private static final String MIGRATOR = "banfield_migrator";
	private static final String RUNTIME = "banfield_runtime";
	private static final String AJENO = "banfield_escritura_ajena";
	private static final String CLAVE = "solo-en-testcontainers";
	private static final String TABLA = "gestion_patin.tutor_deportista";
	private static final String H1 = "a".repeat(64);
	private static final String H2 = "b".repeat(64);
	private static final List<String> FUNCIONES = List.of(
			"gestion_patin.crear_vigencia_tutor(uuid,uuid,uuid,uuid,text,uuid,text)",
			"gestion_patin.actualizar_tipo_vigencia_tutor(uuid,uuid,uuid,bigint,text)",
			"gestion_patin.revocar_vigencia_tutor(uuid,uuid,uuid,bigint)");

	private String base;
	private DriverManagerDataSource admin;
	private DriverManagerDataSource runtime;
	private ExecutorService hilos;

	@BeforeAll
	void preparar() throws Exception {
		hilos = Executors.newCachedThreadPool();
		base = PostgresDescartable.crearBase("escrituras_runtime");
		admin = base(PostgresDescartable.usuario(), PostgresDescartable.clave());
		runtime = base(RUNTIME, CLAVE);
		try (var c = admin.getConnection(); var s = c.createStatement()) {
			s.execute("CREATE ROLE " + OWNER + " NOLOGIN");
			for (String rol : List.of(MIGRATOR, RUNTIME, AJENO)) {
				s.execute("CREATE ROLE " + rol + " LOGIN PASSWORD '" + CLAVE + "'");
			}
			s.execute(Files.readString(Path.of("ops/postgresql/preparar-migracion.sql")));
		}
		flyway().target("15").load().migrate();
		assertThat(flyway().load().migrate().migrationsExecuted).isEqualTo(1);
		flyway().load().validate();
		try (var c = base(MIGRATOR, CLAVE).getConnection(); var s = c.createStatement()) {
			s.execute("SET ROLE " + OWNER);
			for (String script : List.of("c1", "c2a", "c2b")) {
				s.execute(Files.readString(Path.of("ops/postgresql/autorizar-runtime-" + script + ".sql")));
			}
			s.execute("GRANT USAGE ON SCHEMA gestion_patin TO " + AJENO);
		}
	}

	@AfterAll
	void limpiar() throws SQLException {
		hilos.shutdownNow();
		if (base != null) {
			PostgresDescartable.eliminarBase(base);
			var global = new DriverManagerDataSource(PostgresDescartable.url(), PostgresDescartable.usuario(),
					PostgresDescartable.clave());
			try (var c = global.getConnection(); var s = c.createStatement()) {
				s.execute("DROP ROLE IF EXISTS " + String.join(", ", AJENO, RUNTIME, MIGRATOR, OWNER));
			}
		}
	}

	// ---------- permisos ----------

	@Test
	void elRuntimeSoloEjecutaLasFuncionesSinDmlDirectoNiPublico() throws Exception {
		var a = caso();
		try (var c = runtime.getConnection(); var s = c.createStatement()) {
			for (String sql : List.of("INSERT INTO " + TABLA + " DEFAULT VALUES", "UPDATE " + TABLA + " SET tipo='MADRE'",
					"DELETE FROM " + TABLA, "UPDATE gestion_patin_privado.marca_tutor SET estado=false")) {
				assertThatThrownBy(() -> s.execute(sql)).isInstanceOfSatisfying(SQLException.class,
						e -> assertThat(e.getSQLState()).as(sql).isEqualTo("42501"));
			}
		}
		try (var c = base(AJENO, CLAVE).getConnection()) {
			assertThatThrownBy(() -> crear(c, a, a.tutor(), UUID.randomUUID(), H1, null)).isInstanceOfSatisfying(SQLException.class,
					e -> assertThat(e.getSQLState()).isEqualTo("42501"));
		}
		for (String f : FUNCIONES) {
			assertThat(JdbcClient.create(admin).sql("""
					SELECT p.prosecdef AND r.rolname='banfield_schema_owner' AND NOT r.rolcanlogin
					   AND p.proconfig = ARRAY['search_path=pg_catalog, pg_temp']
					   AND NOT has_function_privilege('banfield_escritura_ajena', p.oid, 'EXECUTE')
					   AND has_function_privilege('banfield_runtime', p.oid, 'EXECUTE')
					   AND NOT has_function_privilege('banfield_runtime', p.oid, 'EXECUTE WITH GRANT OPTION')
					FROM pg_proc p JOIN pg_roles r ON p.proowner=r.oid WHERE p.oid=CAST(:f AS regprocedure)
					""").param("f", f).query(Boolean.class).single()).as(f).isTrue();
		}
	}

	@Test
	void rechazaActoresQueNoSonAdminActivoDeLaEscuela() throws Exception {
		var a = caso();
		var b = caso();
		try (var c = runtime.getConnection()) {
			assertThatThrownBy(() -> crearComo(c, a, b.admin(), UUID.randomUUID(), H1, null))
					.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
			assertThatThrownBy(() -> tipo(c, a.escuela(), b.admin(), a.vigencia(), 0, "MADRE"))
					.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
			assertThatThrownBy(() -> revocar(c, a.escuela(), b.admin(), a.vigencia(), 0))
					.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
		}
		assertThat(abierta(a.vigencia())).isTrue();
	}

	// ---------- alta e idempotencia ----------

	@Test
	void altaCreaUnaVigenciaAbiertaConAuditoriaYTipoNuloDistintoDeSinDeterminar() throws Exception {
		var a = caso();
		var tutor2 = tutor(a);
		var solicitud = UUID.randomUUID();
		try (var c = runtime.getConnection()) {
			var r = crear(c, a, tutor2, solicitud, H1, null);
			assertThat(r.creada()).isTrue();
			assertThat(tipoDe(r.id())).isNull();
			assertThat(auditorias(r.id(), "VIGENCIA_TUTOR_CREADA")).isEqualTo(1);
			var otro = tutor(a);
			var s2 = crear(c, a, otro, UUID.randomUUID(), H1, "SIN_DETERMINAR");
			assertThat(tipoDe(s2.id())).isEqualTo("SIN_DETERMINAR");
		}
	}

	@Test
	void repeticionDevuelveLaOriginalTambienCerradaYReutilizarConOtroContenidoEs409() throws Exception {
		var a = caso();
		var tutor2 = tutor(a);
		var solicitud = UUID.randomUUID();
		try (var c = runtime.getConnection()) {
			var primera = crear(c, a, tutor2, solicitud, H1, "MADRE");
			var repetida = crear(c, a, tutor2, solicitud, H1, "MADRE");
			assertThat(repetida.id()).isEqualTo(primera.id());
			assertThat(repetida.creada()).isFalse();
			assertThat(auditorias(primera.id(), "VIGENCIA_TUTOR_CREADA")).isEqualTo(1);
			assertThat(revocar(c, a.escuela(), a.admin(), primera.id(), 0)).isEqualTo(1);
			var tardia = crear(c, a, tutor2, solicitud, H1, "MADRE");
			assertThat(tardia.id()).isEqualTo(primera.id());
			assertThat(tardia.creada()).isFalse();
			assertThat(contar(a, tutor2)).isEqualTo(1);
			assertThatThrownBy(() -> crear(c, a, tutor2, solicitud, H2, "MADRE")).isInstanceOfSatisfying(
					SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("BP405"));
			// Una nueva responsabilidad exige una clave nueva y crea una vigencia nueva; el historial se conserva.
			var nueva = crear(c, a, tutor2, UUID.randomUUID(), H1, "MADRE");
			assertThat(nueva.creada()).isTrue();
			assertThat(nueva.id()).isNotEqualTo(primera.id());
			assertThat(contar(a, tutor2)).isEqualTo(2);
		}
	}

	@Test
	void laClaveSeAcotaALaEscuelaYNoFiltraVigenciasAjenas() throws Exception {
		var a = caso();
		var b = caso();
		var tutorA = tutor(a);
		var tutorB = tutor(b);
		var solicitud = UUID.randomUUID();
		try (var c = runtime.getConnection()) {
			var deA = crear(c, a, tutorA, solicitud, H1, null);
			var deB = crear(c, b, tutorB, solicitud, H1, null);
			assertThat(deB.id()).isNotEqualTo(deA.id());
			assertThat(deB.creada()).isTrue();
			// La clave de A con el actor de B jamás devuelve la vigencia de A.
			assertThatThrownBy(() -> crearComo(c, a, b.admin(), solicitud, H1, null)).isInstanceOfSatisfying(
					SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
		}
	}

	@Test
	void precondicionesDelAltaYAislamientoDeEscuela() throws Exception {
		var a = caso();
		var b = caso();
		var inactivo = tutor(a);
		ejecutarAdmin("UPDATE gestion_patin.tutor SET activo=false WHERE id='" + inactivo + "'");
		try (var c = runtime.getConnection()) {
			assertThatThrownBy(() -> crear(c, a, inactivo, UUID.randomUUID(), H1, null)).isInstanceOfSatisfying(
					SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("BP412"));
			// Tutor o deportista de otra escuela: indistinguible de inexistente.
			assertThatThrownBy(() -> crearDe(c, a.escuela(), a.admin(), a.deportista(), tutor(b), UUID.randomUUID(), H1,
					null)).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("BP404"));
			assertThatThrownBy(() -> crearDe(c, a.escuela(), a.admin(), b.deportista(), tutor(a), UUID.randomUUID(), H1,
					null)).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("BP404"));
			// Sin vínculo familiar activo con la familia del tutor no se infiere la asociación.
			var otraFamilia = new DatosDb(JdbcClient.create(admin)).familia(a.escuela(), "Otra", true);
			var tutorAjeno = new DatosDb(JdbcClient.create(admin)).tutor(a.escuela(), otraFamilia, "Otro", "Tutor");
			assertThatThrownBy(() -> crearDe(c, a.escuela(), a.admin(), a.deportista(), tutorAjeno, UUID.randomUUID(), H1,
					null)).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("BP412"));
		}
		for (String sql : List.of(
				"UPDATE gestion_patin.familia SET activa=false WHERE id='%s'".formatted(a.familia()),
				"UPDATE gestion_patin.deportista SET activo=false WHERE id='%s'".formatted(a.deportista()),
				"UPDATE gestion_patin.familia_deportista SET estado='REVOCADO' WHERE deportista_id='%s'"
						.formatted(a.deportista()))) {
			var x = casoBase();
			var t = tutor(x);
			ejecutarAdmin(sql.replace(a.familia().toString(), x.familia().toString())
					.replace(a.deportista().toString(), x.deportista().toString()));
			try (var c = runtime.getConnection()) {
				assertThatThrownBy(() -> crear(c, x, t, UUID.randomUUID(), H1, null)).as(sql).isInstanceOfSatisfying(
						SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("BP412"));
			}
		}
	}

	@Test
	void otraSolicitudDelMismoParVigenteEsDuplicadoYNoCreaNada() throws Exception {
		var a = caso();
		try (var c = runtime.getConnection()) {
			assertThatThrownBy(() -> crear(c, a, a.tutor(), UUID.randomUUID(), H1, null)).isInstanceOfSatisfying(
					SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("BP413"));
		}
		assertThat(contar(a, a.tutor())).isEqualTo(1);
	}

	@Test
	void unAltaRevertidaNoDejaVigenciaNiAuditoria() throws Exception {
		var a = caso();
		var t = tutor(a);
		try (var c = transaccion(runtime)) {
			crear(c, a, t, UUID.randomUUID(), H1, null);
			c.rollback();
		}
		assertThat(contar(a, t)).isZero();
		assertThat(JdbcClient.create(admin).sql("""
				SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id=:e AND accion='VIGENCIA_TUTOR_CREADA'
				  AND (detalle->>'tutorId')=:t""").param("e", a.escuela()).param("t", t.toString())
				.query(Integer.class).single()).isZero();
	}

	// ---------- edición y revocación ----------

	@Test
	void edicionDeTipoSoloCambiaElTipoYNoAuditaSinCambioNiConRevisionObsoleta() throws Exception {
		var a = caso();
		try (var c = runtime.getConnection()) {
			assertThat(tipo(c, a.escuela(), a.admin(), a.vigencia(), 0, null)).isEqualTo(new Edicion(0, false));
			assertThat(tipo(c, a.escuela(), a.admin(), a.vigencia(), 0, "SIN_DETERMINAR")).isEqualTo(new Edicion(1, true));
			assertThat(tipo(c, a.escuela(), a.admin(), a.vigencia(), 1, "SIN_DETERMINAR")).isEqualTo(new Edicion(1, false));
			assertThat(tipo(c, a.escuela(), a.admin(), a.vigencia(), 1, null)).isEqualTo(new Edicion(2, true));
			assertThat(auditorias(a.vigencia(), "VIGENCIA_TUTOR_TIPO_ACTUALIZADA")).isEqualTo(2);
			assertThatThrownBy(() -> tipo(c, a.escuela(), a.admin(), a.vigencia(), 0, "MADRE")).isInstanceOfSatisfying(
					SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("BP414"));
			assertThatThrownBy(() -> tipo(c, a.escuela(), a.admin(), a.vigencia(), 2, "NO_EXISTE")).isInstanceOfSatisfying(
					SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("22023"));
			var b = caso();
			assertThatThrownBy(() -> tipo(c, b.escuela(), b.admin(), a.vigencia(), 2, "MADRE")).isInstanceOfSatisfying(
					SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("BP404"));
		}
	}

	@Test
	void revocacionCierraSinReabrirNiAuditarDeMasYLaReactivacionDelPadreNoLaReabre() throws Exception {
		var a = caso();
		try (var c = runtime.getConnection()) {
			assertThat(revocar(c, a.escuela(), a.admin(), a.vigencia(), 0)).isEqualTo(1);
			assertThat(JdbcClient.create(admin).sql("SELECT causa_cierre FROM " + TABLA + " WHERE id=:id")
					.param("id", a.vigencia()).query(String.class).single()).isEqualTo("REVOCACION_ADMIN");
			assertThat(auditorias(a.vigencia(), "VIGENCIA_TUTOR_CERRADA")).isEqualTo(1);
			assertThatThrownBy(() -> revocar(c, a.escuela(), a.admin(), a.vigencia(), 0)).isInstanceOfSatisfying(
					SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("BP414"));
			assertThatThrownBy(() -> revocar(c, a.escuela(), a.admin(), a.vigencia(), 1)).isInstanceOfSatisfying(
					SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("BP412"));
			assertThatThrownBy(() -> tipo(c, a.escuela(), a.admin(), a.vigencia(), 1, "MADRE")).isInstanceOfSatisfying(
					SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("BP412"));
		}
		ejecutarAdmin("UPDATE gestion_patin.tutor SET activo=false WHERE id='" + a.tutor() + "'");
		ejecutarAdmin("UPDATE gestion_patin.tutor SET activo=true WHERE id='" + a.tutor() + "'");
		assertThat(abierta(a.vigencia())).isFalse();
		assertThat(auditorias(a.vigencia(), "VIGENCIA_TUTOR_CERRADA")).isEqualTo(1);
		assertThatThrownBy(() -> ejecutarAdmin("UPDATE " + TABLA + " SET cerrado_en=NULL, cerrado_por=NULL, "
				+ "causa_cierre=NULL WHERE id='" + a.vigencia() + "'")).isInstanceOfSatisfying(SQLException.class,
						e -> assertThat(e.getSQLState()).isEqualTo("23514"));
	}

	// ---------- sondas R1-001 y R3-001: cada FK de actor por separado ----------

	@Test
	void cadaFkDeActorRechazaPorSiSolaConLosDemasCamposValidos() throws Exception {
		var a = caso();
		var b = caso();
		var t = tutor(a);
		try (var c = admin.getConnection(); var s = c.prepareStatement("""
				INSERT INTO gestion_patin.tutor_deportista (escuela_id, familia_id, deportista_id, tutor_id, solicitud_id,
				  huella_solicitud, creado_por) VALUES (?,?,?,?,?,?,?)""")) {
			s.setObject(1, a.escuela());
			s.setObject(2, a.familia());
			s.setObject(3, a.deportista());
			s.setObject(4, t);
			s.setObject(5, UUID.randomUUID());
			s.setString(6, H1);
			s.setObject(7, b.admin());
			assertThatThrownBy(s::executeUpdate).hasMessageContaining("fk_td_creado_por_misma_escuela");
		}
		try (var c = admin.getConnection(); var s = c.prepareStatement("""
				UPDATE gestion_patin.tutor_deportista SET cerrado_en=now(), cerrado_por=?, causa_cierre='REVOCACION_ADMIN'
				WHERE id=?""")) {
			s.setObject(1, b.admin());
			s.setObject(2, a.vigencia());
			assertThatThrownBy(s::executeUpdate).hasMessageContaining("fk_td_cerrado_por_misma_escuela");
		}
		assertThat(abierta(a.vigencia())).isTrue();
	}

	// ---------- concurrencia con los cierres por baja ----------

	private enum Padre {
		TUTOR("TUTOR_INACTIVO", "UPDATE gestion_patin.tutor SET activo=false WHERE id='%s'"),
		FAMILIA("FAMILIA_INACTIVA", "UPDATE gestion_patin.familia SET activa=false WHERE id='%s'"),
		DEPORTISTA("DEPORTISTA_INACTIVO", "UPDATE gestion_patin.deportista SET activo=false WHERE id='%s'"),
		REVOCACION("FAMILIA_DEPORTISTA_REVOCADA",
				"UPDATE gestion_patin.familia_deportista SET estado='REVOCADO' WHERE deportista_id='%s'");

		final String causa;
		final String baja;

		Padre(String causa, String baja) {
			this.causa = causa;
			this.baja = baja;
		}

		UUID objetivo(Caso c, UUID tutorNuevo) {
			return switch (this) {
				case TUTOR -> tutorNuevo;
				case FAMILIA -> c.familia();
				case DEPORTISTA, REVOCACION -> c.deportista();
			};
		}
	}

	@ParameterizedTest
	@EnumSource(Padre.class)
	void altaPrimeroLaBajaEsperaYCierraTambienLaVigenciaNueva(Padre padre) throws Exception {
		var a = caso();
		var t = tutor(a);
		try (var alta = transaccion(runtime); var baja = transaccion(runtime)) {
			var nueva = crear(alta, a, t, UUID.randomUUID(), H1, null);
			int pidAlta = pid(alta);
			int pidBaja = pid(baja);
			var cierre = hilos.submit(() -> {
				var ids = cerrar(baja, a, padre, t);
				ejecutar(baja, padre.baja.formatted(padre.objetivo(a, t)));
				baja.commit();
				return ids;
			});
			esperarBloqueo(pidAlta, pidBaja);
			alta.commit();
			assertThat(cierre.get(5, TimeUnit.SECONDS)).contains(nueva.id());
			assertThat(abierta(nueva.id())).isFalse();
		}
	}

	@ParameterizedTest
	@EnumSource(Padre.class)
	void bajaPrimeroElAltaEsperaYLuegoEsEstadoIncompatible(Padre padre) throws Exception {
		var a = caso();
		var t = tutor(a);
		try (var baja = transaccion(runtime); var alta = transaccion(runtime)) {
			cerrar(baja, a, padre, t);
			ejecutar(baja, padre.baja.formatted(padre.objetivo(a, t)));
			int pidBaja = pid(baja);
			int pidAlta = pid(alta);
			var intento = hilos.submit(() -> crear(alta, a, t, UUID.randomUUID(), H1, null));
			esperarBloqueo(pidBaja, pidAlta);
			baja.commit();
			assertThatThrownBy(() -> intento.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(SQLException.class)
					.rootCause().satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("BP412"));
		}
		assertThat(contar(a, t)).isZero();
	}

	@Test
	void dosAltasDeLaMismaClaveConcurrentesCreanUnaSolaVigencia() throws Exception {
		var a = caso();
		var t = tutor(a);
		var solicitud = UUID.randomUUID();
		try (var uno = transaccion(runtime); var dos = transaccion(runtime)) {
			var primera = crear(uno, a, t, solicitud, H1, null);
			int pidUno = pid(uno);
			int pidDos = pid(dos);
			var segunda = hilos.submit(() -> {
				var r = crear(dos, a, t, solicitud, H1, null);
				dos.commit();
				return r;
			});
			esperarBloqueo(pidUno, pidDos);
			uno.commit();
			var r2 = segunda.get(5, TimeUnit.SECONDS);
			assertThat(r2.id()).isEqualTo(primera.id());
			assertThat(r2.creada()).isFalse();
		}
		assertThat(contar(a, t)).isEqualTo(1);
	}

	@Test
	void dosAltasDelMismoParConClavesDistintasDejanUnaYUnDuplicado() throws Exception {
		var a = caso();
		var t = tutor(a);
		try (var uno = transaccion(runtime); var dos = transaccion(runtime)) {
			crear(uno, a, t, UUID.randomUUID(), H1, null);
			int pidUno = pid(uno);
			int pidDos = pid(dos);
			var segunda = hilos.submit(() -> crear(dos, a, t, UUID.randomUUID(), H2, null));
			esperarBloqueo(pidUno, pidDos);
			uno.commit();
			assertThatThrownBy(() -> segunda.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(SQLException.class)
					.rootCause().satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("BP413"));
		}
		assertThat(contar(a, t)).isEqualTo(1);
	}

	@Test
	void edicionConcurrenteContraRevocacionDejaUnGanadorYElPerdedorVeRevisionObsoleta() throws Exception {
		var a = caso();
		try (var revoca = transaccion(runtime); var edita = transaccion(runtime)) {
			assertThat(revocar(revoca, a.escuela(), a.admin(), a.vigencia(), 0)).isEqualTo(1);
			int pidRevoca = pid(revoca);
			int pidEdita = pid(edita);
			var intento = hilos.submit(() -> tipo(edita, a.escuela(), a.admin(), a.vigencia(), 0, "MADRE"));
			esperarBloqueo(pidRevoca, pidEdita);
			revoca.commit();
			assertThatThrownBy(() -> intento.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(SQLException.class)
					.rootCause().satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("BP414"));
		}
		assertThat(tipoDe(a.vigencia())).isNull();
		assertThat(auditorias(a.vigencia(), "VIGENCIA_TUTOR_TIPO_ACTUALIZADA")).isZero();
	}

	@Test
	void bajoRepeatableReadUnaClaveDuplicadaConcurrenteSeReintentaYDevuelveLaOriginal() throws Exception {
		var a = caso();
		var t = tutor(a);
		var solicitud = UUID.randomUUID();
		try (var uno = transaccion(runtime); var dos = transaccion(runtime)) {
			ejecutar(dos, "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
			ejecutar(dos, "SELECT 1");
			var primera = crear(uno, a, t, solicitud, H1, null);
			int pidUno = pid(uno);
			int pidDos = pid(dos);
			var segunda = hilos.submit(() -> crear(dos, a, t, solicitud, H1, null));
			esperarBloqueo(pidUno, pidDos);
			uno.commit();
			assertThatThrownBy(() -> segunda.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(SQLException.class)
					.rootCause().satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("40001"));
			dos.rollback();
			try (var reintento = transaccion(runtime)) {
				var r = crear(reintento, a, t, solicitud, H1, null);
				assertThat(r.id()).isEqualTo(primera.id());
				assertThat(r.creada()).isFalse();
			}
		}
	}

	// ---------- soporte ----------

	private record Caso(UUID escuela, UUID admin, UUID familia, UUID deportista, UUID tutor, UUID vigencia) {}

	private record Alta(UUID id, boolean creada) {}

	private record Edicion(long version, boolean cambio) {}

	private Caso caso() {
		var base = casoBase();
		var vigencia = JdbcClient.create(admin).sql("""
				INSERT INTO gestion_patin.tutor_deportista (escuela_id, familia_id, deportista_id, tutor_id, solicitud_id,
				  huella_solicitud, creado_por) VALUES (:e,:f,:d,:t,gen_random_uuid(),:h,:a) RETURNING id
				""").param("e", base.escuela()).param("f", base.familia()).param("d", base.deportista())
				.param("t", base.tutor()).param("h", H1).param("a", base.admin()).query(UUID.class).single();
		return new Caso(base.escuela(), base.admin(), base.familia(), base.deportista(), base.tutor(), vigencia);
	}

	private Caso casoBase() {
		var datos = new DatosDb(JdbcClient.create(admin));
		String clave = UUID.randomUUID().toString();
		var escuela = datos.escuela(clave);
		var actor = datos.admin(escuela, "admin@" + clave + ".invalid", true);
		var familia = datos.familia(escuela, "Familia", true);
		var deportista = datos.deportista(escuela, "PRUEBA", "Deportista", "Prueba");
		datos.vinculo(escuela, familia, deportista, "ACTIVO", false, actor);
		var tutor = datos.tutor(escuela, familia, "Tutor", "Prueba");
		return new Caso(escuela, actor, familia, deportista, tutor, null);
	}

	private UUID tutor(Caso c) {
		return new DatosDb(JdbcClient.create(admin)).tutor(c.escuela(), c.familia(), "Otro", "Tutor");
	}

	private Alta crear(Connection c, Caso caso, UUID tutor, UUID solicitud, String huella, String tipo)
			throws SQLException {
		return crearDe(c, caso.escuela(), caso.admin(), caso.deportista(), tutor, solicitud, huella, tipo);
	}

	private Alta crearComo(Connection c, Caso caso, UUID actor, UUID solicitud, String huella, String tipo)
			throws SQLException {
		return crearDe(c, caso.escuela(), actor, caso.deportista(), caso.tutor(), solicitud, huella, tipo);
	}

	private Alta crearDe(Connection c, UUID escuela, UUID actor, UUID deportista, UUID tutor, UUID solicitud,
			String huella, String tipo) throws SQLException {
		try (var s = c.prepareStatement("SELECT * FROM gestion_patin.crear_vigencia_tutor(?,?,?,?,?,?,?)")) {
			s.setObject(1, escuela);
			s.setObject(2, actor);
			s.setObject(3, deportista);
			s.setObject(4, tutor);
			s.setString(5, tipo);
			s.setObject(6, solicitud);
			s.setString(7, huella);
			try (var r = s.executeQuery()) {
				assertThat(r.next()).isTrue();
				return new Alta(r.getObject(1, UUID.class), r.getBoolean(2));
			}
		}
	}

	private Edicion tipo(Connection c, UUID escuela, UUID actor, UUID vigencia, long version, String tipo)
			throws SQLException {
		try (var s = c.prepareStatement("SELECT * FROM gestion_patin.actualizar_tipo_vigencia_tutor(?,?,?,?,?)")) {
			s.setObject(1, escuela);
			s.setObject(2, actor);
			s.setObject(3, vigencia);
			s.setLong(4, version);
			s.setString(5, tipo);
			try (var r = s.executeQuery()) {
				assertThat(r.next()).isTrue();
				return new Edicion(r.getLong(1), r.getBoolean(2));
			}
		}
	}

	private long revocar(Connection c, UUID escuela, UUID actor, UUID vigencia, long version) throws SQLException {
		try (var s = c.prepareStatement("SELECT gestion_patin.revocar_vigencia_tutor(?,?,?,?)")) {
			s.setObject(1, escuela);
			s.setObject(2, actor);
			s.setObject(3, vigencia);
			s.setLong(4, version);
			try (var r = s.executeQuery()) {
				assertThat(r.next()).isTrue();
				return r.getLong(1);
			}
		}
	}

	private List<UUID> cerrar(Connection c, Caso a, Padre padre, UUID tutorNuevo) throws SQLException {
		try (var s = c.prepareStatement("SELECT * FROM gestion_patin.cerrar_vigencias_tutor(?,?,?,?,?)")) {
			s.setObject(1, a.escuela());
			s.setObject(2, a.admin());
			s.setString(3, padre.causa);
			s.setObject(4, padre.objetivo(a, tutorNuevo));
			s.setObject(5, padre == Padre.TUTOR || padre == Padre.REVOCACION ? a.familia() : null);
			try (var rs = s.executeQuery()) {
				var ids = new java.util.ArrayList<UUID>();
				while (rs.next()) {
					ids.add(rs.getObject(1, UUID.class));
				}
				return ids;
			}
		}
	}

	private static void ejecutar(Connection c, String sql) throws SQLException {
		try (var s = c.createStatement()) {
			s.execute(sql);
		}
	}

	private void ejecutarAdmin(String sql) throws SQLException {
		try (var c = admin.getConnection()) {
			ejecutar(c, sql);
		}
	}

	private int contar(Caso c, UUID tutor) {
		return JdbcClient.create(admin).sql("SELECT count(*) FROM " + TABLA + " WHERE escuela_id=:e AND tutor_id=:t")
				.param("e", c.escuela()).param("t", tutor).query(Integer.class).single();
	}

	private int auditorias(UUID recurso, String accion) {
		return JdbcClient.create(admin).sql("""
				SELECT count(*) FROM gestion_patin.auditoria WHERE recurso_id=:r AND accion=:a
				  AND recurso_tipo='TUTOR_DEPORTISTA'""").param("r", recurso).param("a", accion)
				.query(Integer.class).single();
	}

	private String tipoDe(UUID vigencia) {
		return JdbcClient.create(admin).sql("SELECT tipo FROM " + TABLA + " WHERE id=:id").param("id", vigencia)
				.query(String.class).optional().orElse(null);
	}

	private boolean abierta(UUID vigencia) {
		return JdbcClient.create(admin).sql("SELECT cerrado_en IS NULL FROM " + TABLA + " WHERE id=:id")
				.param("id", vigencia).query(Boolean.class).single();
	}

	private static Connection transaccion(DriverManagerDataSource fuente) throws SQLException {
		var c = fuente.getConnection();
		try {
			c.setAutoCommit(false);
			ejecutar(c, "SET LOCAL lock_timeout='3s'");
			ejecutar(c, "SET LOCAL statement_timeout='5s'");
			return c;
		} catch (SQLException ex) {
			c.close();
			throw ex;
		}
	}

	private static int pid(Connection c) throws SQLException {
		try (var s = c.createStatement(); var r = s.executeQuery("SELECT pg_backend_pid()")) {
			assertThat(r.next()).isTrue();
			return r.getInt(1);
		}
	}

	private void esperarBloqueo(int bloqueador, int bloqueado) throws Exception {
		long limite = System.nanoTime() + Duration.ofSeconds(3).toNanos();
		try (var c = admin.getConnection(); var s = c.prepareStatement("SELECT ?=ANY(pg_blocking_pids(?))")) {
			s.setInt(1, bloqueador);
			s.setInt(2, bloqueado);
			do {
				try (var r = s.executeQuery()) {
					assertThat(r.next()).isTrue();
					if (r.getBoolean(1)) {
						return;
					}
				}
				Thread.sleep(10);
			} while (System.nanoTime() < limite);
		}
		throw new AssertionError("La segunda transacción no esperó a la primera");
	}

	private org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
		return Flyway.configure().dataSource(PostgresDescartable.urlDeBase(base), MIGRATOR, CLAVE)
				.initSql("SET ROLE " + OWNER).schemas("gestion_patin").defaultSchema("gestion_patin").cleanDisabled(true);
	}

	private DriverManagerDataSource base(String usuario, String clave) {
		return new DriverManagerDataSource(PostgresDescartable.urlDeBase(base), usuario, clave);
	}
}
