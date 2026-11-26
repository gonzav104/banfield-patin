package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V14MarcasPrivadasDbTest {
	private static final String OWNER = "banfield_schema_owner";
	private static final String MIGRATOR = "banfield_migrator";
	private static final String RUNTIME = "banfield_runtime";
	private static final String WRITER = "banfield_writer_probe";
	private static final String CLAVE_PRUEBA = "solo-en-testcontainers";
	private static final String[] ROLES = { WRITER, RUNTIME, MIGRATOR, OWNER };
	private static final String TD = "gestion_patin.tutor_deportista";

	private enum Padre {
		FAMILIA("familia", "activa", "true", "false", Caso::familia),
		DEPORTISTA("deportista", "activo", "true", "false", Caso::deportista),
		VINCULO("familia_deportista", "estado", "'ACTIVO'", "'REVOCADO'", Caso::vinculo),
		TUTOR("tutor", "activo", "true", "false", Caso::tutor);

		private final String tabla, estado, activo, inactivo;
		private final Function<Caso, UUID> id;

		Padre(String tabla, String estado, String activo, String inactivo, Function<Caso, UUID> id) {
			this.tabla = tabla;
			this.estado = estado;
			this.activo = activo;
			this.inactivo = inactivo;
			this.id = id;
		}

		String cambiar(Caso caso, boolean activar) {
			return "UPDATE gestion_patin." + tabla + " SET " + estado + "=" + (activar ? activo : inactivo)
					+ " WHERE id=" + literal(id.apply(caso));
		}

		String consultar(Caso caso) {
			return "SELECT " + estado + "=" + activo + " FROM gestion_patin." + tabla
					+ " WHERE id=" + literal(id.apply(caso));
		}
	}

	private record Caso(UUID escuela, UUID familia, UUID deportista, UUID vinculo, UUID tutor, UUID admin) {
		String alta(UUID solicitud) {
			return """
					INSERT INTO gestion_patin.tutor_deportista
					    (escuela_id, familia_id, deportista_id, tutor_id, creado_por, solicitud_id, huella_solicitud)
					VALUES (%s, %s, %s, %s, %s, %s, repeat('a', 64))
					""".formatted(literal(escuela), literal(familia), literal(deportista),
					literal(tutor), literal(admin), literal(solicitud));
		}

		String cantidad(boolean abiertas) {
			return "SELECT count(*) FROM " + TD + " WHERE escuela_id=" + literal(escuela)
					+ (abiertas ? " AND cerrado_en IS NULL" : "");
		}
	}

	private String base;
	private DriverManagerDataSource admin;
	private Caso previo;

	@BeforeAll
	void migrarConRolesSeparados() throws Exception {
		base = PostgresDescartable.crearBase("roles");
		admin = new DriverManagerDataSource(PostgresDescartable.urlDeBase(base), PostgresDescartable.usuario(),
				PostgresDescartable.clave());
		try (var c = admin.getConnection()) {
			execute(c, "CREATE ROLE " + OWNER + " NOLOGIN");
			for (String rol : List.of(MIGRATOR, RUNTIME, WRITER)) {
				execute(c, "CREATE ROLE " + rol + " LOGIN PASSWORD '" + CLAVE_PRUEBA + "'");
			}
			execute(c, Files.readString(Path.of("ops/postgresql/preparar-migracion.sql")));
		}
		assertThat(flyway("13").migrate().migrationsExecuted).isEqualTo(13);
		previo = caso();
		assertThat(flyway("14").migrate().migrationsExecuted).isEqualTo(1);
		flyway("14").validate();
		try (var c = login(MIGRATOR)) {
			execute(c, "SET ROLE " + OWNER);
			for (Padre padre : Padre.values()) {
				assertThat(scalar(c, "SELECT estado::text FROM gestion_patin_privado.marca_" + padre.tabla))
						.isEqualTo(padre == Padre.VINCULO ? "ACTIVO" : "true");
			}
			assertThat(scalar(c, previo.cantidad(false))).isEqualTo("0");
			execute(c, Files.readString(Path.of("ops/postgresql/autorizar-runtime-c1.sql")));
			// Este login sólo existe en el contenedor para probar SQL directo con las futuras escrituras.
			execute(c, "GRANT INSERT, UPDATE ON " + TD + " TO " + WRITER);
		}
		try (var c = admin.getConnection()) {
			execute(c, "GRANT " + RUNTIME + " TO " + WRITER + " WITH INHERIT TRUE, SET FALSE");
		}
	}

	@AfterAll
	void descartar() throws SQLException {
		if (base != null) {
			PostgresDescartable.eliminarBase(base);
		}
		if (admin != null) {
			try (var c = new DriverManagerDataSource(PostgresDescartable.url(), PostgresDescartable.usuario(),
					PostgresDescartable.clave()).getConnection()) {
				execute(c, "DROP ROLE IF EXISTS " + String.join(", ", ROLES));
			}
		}
	}

	@Test
	void rolDeAplicacionOperaSinPrivilegiosDeMigracionNiDeMarcas() throws Exception {
		try (var c = login(RUNTIME); var migracion = login(MIGRATOR)) {
			assertThat(scalar(c, "SELECT current_user")).isEqualTo(RUNTIME);
			assertThat(scalar(migracion, "SELECT current_user")).isEqualTo(MIGRATOR);
			assertThat(scalar(c, previo.cantidad(false))).isEqualTo("0");
			assertThat(execute(c, Padre.FAMILIA.cambiar(previo, false))).isOne();
			assertThat(execute(c, Padre.FAMILIA.cambiar(previo, true))).isOne();
			assertThat(scalar(c, Padre.FAMILIA.consultar(previo))).isEqualTo("t");
			assertThat(execute(c, """
					INSERT INTO gestion_patin.auditoria (escuela_id, accion, recurso_tipo)
					VALUES (%s, 'PRUEBA_ROL', 'familia')
					""".formatted(literal(previo.escuela())))).isOne();

			assertThat(scalar(c, """
					SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON p.pronamespace=n.oid
					WHERE n.nspname='gestion_patin_privado' AND p.proname='sincronizar_marca_vigencia' AND p.prosecdef
					  AND pg_get_userbyid(p.proowner)='banfield_schema_owner'
					  AND p.proconfig @> ARRAY['search_path=pg_catalog, pg_temp']
					  AND NOT has_function_privilege(p.oid, 'EXECUTE')
					""")).isEqualTo("1");
			assertThat(scalar(c, """
					SELECT pg_has_role('banfield_schema_owner', 'MEMBER')
					    OR pg_has_role('banfield_migrator', 'MEMBER')
					    OR has_parameter_privilege('session_replication_role', 'SET')
					""")).isEqualTo("f");
			for (var padre : Padre.values()) {
				var marca = "gestion_patin_privado.marca_" + padre.tabla;
				assertThat(scalar(c, """
						SELECT bool_or(has_table_privilege(t.oid, permiso))
						FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace CROSS JOIN unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE',
						    'TRUNCATE', 'REFERENCES', 'TRIGGER']) permiso
						WHERE n.nspname='gestion_patin_privado' AND t.relname='marca_%s'
						""".formatted(padre.tabla))).isEqualTo("f");
				for (String sql : List.of("SELECT * FROM " + marca, "INSERT INTO " + marca + " DEFAULT VALUES",
						"UPDATE " + marca + " SET estado=DEFAULT", "DELETE FROM " + marca, "TRUNCATE " + marca,
						"ALTER TABLE gestion_patin." + padre.tabla + " DISABLE TRIGGER ALL",
						"ALTER TABLE gestion_patin." + padre.tabla + " DISABLE TRIGGER trg_marca_vigencia_alta",
						"ALTER TABLE gestion_patin." + padre.tabla + " ENABLE ALWAYS TRIGGER trg_marca_vigencia")) {
					assertThatThrownBy(() -> execute(c, sql)).isInstanceOfSatisfying(SQLException.class,
							e -> assertThat(e.getSQLState()).as(sql).isEqualTo("42501"));
				}
			}
			for (String sql : List.of("SET ROLE " + OWNER, "SET ROLE " + MIGRATOR, "SET session_replication_role=replica",
					"SELECT * FROM gestion_patin.flyway_schema_history", "TRUNCATE " + TD,
					"UPDATE gestion_patin.escuela SET activa=false",
					"ALTER TABLE " + TD + " DISABLE TRIGGER USER",
					"ALTER FUNCTION gestion_patin_privado.sincronizar_marca_vigencia() SECURITY INVOKER",
					"CREATE TABLE gestion_patin.no_autorizada (id int)",
					"CREATE TABLE gestion_patin_privado.no_autorizada (id int)", previo.alta(UUID.randomUUID()))) {
				assertThatThrownBy(() -> execute(c, sql)).isInstanceOfSatisfying(SQLException.class,
						e -> assertThat(e.getSQLState()).as(sql).isEqualTo("42501"));
			}
		}
	}

	@Test
	void autorizarRuntimeRechazaPrivilegiosPublicosPorColumnaEnHistorial() throws Exception {
		var script = Files.readString(Path.of("ops/postgresql/autorizar-runtime-c1.sql"));
		try (var owner = login(MIGRATOR)) {
			execute(owner, "SET ROLE " + OWNER);
			execute(owner, "GRANT UPDATE (description) ON gestion_patin.flyway_schema_history TO PUBLIC");
			try {
				assertThatThrownBy(() -> execute(owner, script)).isInstanceOfSatisfying(SQLException.class,
						e -> assertThat(e.getSQLState()).isEqualTo("P0001"));
			} finally {
				execute(owner, "ROLLBACK");
				execute(owner, "REVOKE UPDATE (description) ON gestion_patin.flyway_schema_history FROM PUBLIC");
			}
		}
	}

	@ParameterizedTest
	@EnumSource(Padre.class)
	void cambioDeEstadoCompatibleConKeyShareConVigenciasVacias(Padre padre) throws Exception {
		var caso = caso();
		try (var lector = transaccion("READ COMMITTED"); var escritor = transaccion("READ COMMITTED")) {
			execute(lector, "SELECT 1 FROM gestion_patin." + padre.tabla
					+ " WHERE id=" + literal(padre.id.apply(caso)) + " FOR KEY SHARE");
			execute(escritor, "SET LOCAL lock_timeout='250ms'");
			assertThat(execute(escritor, padre.cambiar(caso, false))).isOne();
			escritor.commit();
			lector.commit();
			assertThat(scalar(escritor, padre.consultar(caso))).isEqualTo("f");
		}
	}

	static Stream<Arguments> carreras() {
		return Arrays.stream(Padre.values()).flatMap(padre -> niveles().flatMap(nivel ->
				Stream.of(false, true).flatMap(historial -> Stream.of(false, true)
						.map(altaPrimero -> arguments(padre, nivel, historial, altaPrimero)))));
	}

	static Stream<Arguments> aislamientos() {
		return Arrays.stream(Padre.values()).flatMap(padre -> niveles().map(nivel -> arguments(padre, nivel)));
	}

	private static Stream<String> niveles() {
		return Stream.of("READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE");
	}

	@ParameterizedTest(name = "{0} / {1} / vigencia cerrada previa={2} / alta primero={3}")
	@MethodSource("carreras")
	void altaYBajaSonIncompatibles(Padre padre, String nivel, boolean historial, boolean altaPrimero) throws Exception {
		var caso = caso();
		if (historial) {
			sembrarYCerrar(caso);
		}
		try (var executor = Executors.newVirtualThreadPerTaskExecutor();
				var primero = transaccion(nivel); var segundo = transaccion(nivel)) {
			var pidPrimero = scalar(primero, "SELECT pg_backend_pid()");
			var pidSegundo = scalar(segundo, "SELECT pg_backend_pid()");
			var alta = caso.alta(UUID.randomUUID());
			var baja = padre.cambiar(caso, false);
			assertThat(execute(primero, altaPrimero ? alta : baja)).isOne();
			var resultado = executor.submit(() -> escribirYConfirmar(segundo, altaPrimero ? baja : alta));
			esperarBloqueo(pidPrimero, pidSegundo);
			primero.commit();
			assertThat(resultado.get(4, TimeUnit.SECONDS)).isIn("23503", "40001");
		}
		try (var control = login(RUNTIME)) {
			assertThat(scalar(control, padre.consultar(caso))).isEqualTo(altaPrimero ? "t" : "f");
			assertThat(scalar(control, caso.cantidad(true))).isEqualTo(altaPrimero ? "1" : "0");
		}
	}

	@ParameterizedTest(name = "{0} / {1}")
	@MethodSource("aislamientos")
	void unaBajaReactivadaNoReabreHistorialYUnaNuevaAltaEsExplicita(Padre padre, String nivel) throws Exception {
		var caso = caso();
		var anterior = sembrarYCerrar(caso);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor();
				var estados = transaccion(nivel); var alta = transaccion(nivel)) {
			var pidEstados = scalar(estados, "SELECT pg_backend_pid()");
			var pidAlta = scalar(alta, "SELECT pg_backend_pid()");
			assertThat(execute(estados, padre.cambiar(caso, false))).isOne();
			assertThat(execute(estados, padre.cambiar(caso, true))).isOne();
			var resultado = executor.submit(() -> escribirYConfirmar(alta, caso.alta(UUID.randomUUID())));
			esperarBloqueo(pidEstados, pidAlta);
			estados.commit();
			assertThat(resultado.get(4, TimeUnit.SECONDS)).isEqualTo(nivel.equals("READ COMMITTED") ? "OK" : "40001");
		}
		try (var control = login(WRITER)) {
			assertThat(scalar(control, "SELECT cerrado_en IS NOT NULL FROM " + TD
					+ " WHERE solicitud_id=" + literal(anterior))).isEqualTo("t");
			assertThat(scalar(control, caso.cantidad(false))).isEqualTo(nivel.equals("READ COMMITTED") ? "2" : "1");
			assertThatThrownBy(() -> execute(control, caso.alta(anterior))).isInstanceOfSatisfying(SQLException.class,
					e -> assertThat(e.getSQLState()).isEqualTo("23505"));
		}
	}

	@Test
	void timeoutYRollbackNoDejanVigenciaNiAlteranMarcas() throws Exception {
		var caso = caso();
		try (var primero = transaccion("READ COMMITTED"); var segundo = transaccion("READ COMMITTED")) {
			assertThat(execute(primero, caso.alta(UUID.randomUUID()))).isOne();
			execute(segundo, "SET LOCAL lock_timeout='250ms'");
			assertThat(escribirYConfirmar(segundo, Padre.FAMILIA.cambiar(caso, false))).isEqualTo("55P03");
			primero.rollback();
			assertThat(scalar(segundo, caso.cantidad(false))).isEqualTo("0");
			try (var owner = login(MIGRATOR)) {
				execute(owner, "SET ROLE " + OWNER);
				for (var padre : Padre.values()) {
					assertThat(scalar(owner, "SELECT estado::text FROM gestion_patin_privado.marca_" + padre.tabla
							+ " WHERE escuela_id=" + literal(caso.escuela())))
							.isEqualTo(padre == Padre.VINCULO ? "ACTIVO" : "true");
				}
			}
			assertThat(execute(segundo, Padre.FAMILIA.cambiar(caso, false))).isOne();
			segundo.commit();
		}
	}

	@Test
	void altasDistintasDeDosFamiliasEnOrdenInversoConfirmanAmbas() throws Exception {
		var a = caso();
		var b = caso();
		var otroA = otroPar(a);
		var otroB = otroPar(b);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor();
				var primero = transaccion("READ COMMITTED"); var segundo = transaccion("READ COMMITTED")) {
			assertThat(execute(primero, a.alta(UUID.randomUUID()))).isOne();
			assertThat(execute(segundo, b.alta(UUID.randomUUID()))).isOne();
			var resultadoPrimero = executor.submit(() -> escribirYConfirmar(primero, otroB.alta(UUID.randomUUID())));
			var resultadoSegundo = executor.submit(() -> escribirYConfirmar(segundo, otroA.alta(UUID.randomUUID())));
			assertThat(List.of(resultadoPrimero.get(4, TimeUnit.SECONDS), resultadoSegundo.get(4, TimeUnit.SECONDS)))
					.containsExactly("OK", "OK");
		}
		try (var control = login(RUNTIME)) {
			assertThat(scalar(control, a.cantidad(true))).isEqualTo("2");
			assertThat(scalar(control, b.cantidad(true))).isEqualTo("2");
		}
	}

	@Test
	void repetirSolicitudCerradaConFamiliaInactivaEsUnaOperacionNula() throws SQLException {
		var caso = caso();
		var solicitud = sembrarYCerrar(caso);
		try (var escritor = login(WRITER)) {
			assertThat(execute(escritor, Padre.FAMILIA.cambiar(caso, false))).isOne();
			assertThat(execute(escritor, caso.alta(solicitud)
					+ " ON CONFLICT (escuela_id, solicitud_id) DO NOTHING")).isZero();
			assertThat(scalar(escritor, caso.cantidad(false))).isEqualTo("1");
			assertThat(scalar(escritor, caso.cantidad(true))).isEqualTo("0");
			assertThat(scalar(escritor, Padre.FAMILIA.consultar(caso))).isEqualTo("f");
		}
	}

	private Caso otroPar(Caso caso) {
		var datos = new DatosDb(JdbcClient.create(admin));
		var deportista = datos.deportista(caso.escuela(), "R-17-OTRO", "Otro", "Deportista");
		var vinculo = datos.vinculo(caso.escuela(), caso.familia(), deportista, "ACTIVO", false, caso.admin());
		var tutor = datos.tutor(caso.escuela(), caso.familia(), "Otro", "Tutor");
		return new Caso(caso.escuela(), caso.familia(), deportista, vinculo, tutor, caso.admin());
	}

	private Caso caso() {
		String clave = UUID.randomUUID().toString();
		var datos = new DatosDb(JdbcClient.create(admin));
		var e = datos.escuela(clave);
		var a = datos.admin(e, "admin@" + clave + ".invalid", true);
		var f = datos.familia(e, "Familia", true);
		var d = datos.deportista(e, "R-17", "Deportista", "Prueba");
		var v = datos.vinculo(e, f, d, "ACTIVO", false, a);
		var t = datos.tutor(e, f, "Tutor", "Prueba");
		return new Caso(e, f, d, v, t, a);
	}

	private UUID sembrarYCerrar(Caso caso) throws SQLException {
		var id = UUID.randomUUID();
		try (var c = login(WRITER)) {
			assertThat(execute(c, caso.alta(id))).isOne();
			assertThat(execute(c, "UPDATE " + TD + """
					 SET cerrado_en=clock_timestamp(), cerrado_por=creado_por, causa_cierre='REVOCACION_ADMIN'
					 WHERE solicitud_id=
					""".stripTrailing() + literal(id))).isOne();
			return id;
		}
	}

	private Flyway flyway(String version) {
		return Flyway.configure().dataSource(PostgresDescartable.urlDeBase(base), MIGRATOR, CLAVE_PRUEBA)
				.initSql("SET ROLE " + OWNER).schemas("gestion_patin").defaultSchema("gestion_patin")
				.target(version).cleanDisabled(true).load();
	}

	private Connection login(String rol) throws SQLException {
		return new DriverManagerDataSource(PostgresDescartable.urlDeBase(base), rol, CLAVE_PRUEBA).getConnection();
	}

	private Connection transaccion(String nivel) throws SQLException {
		var c = login(WRITER);
		try {
			c.setAutoCommit(false);
			execute(c, "SET TRANSACTION ISOLATION LEVEL " + nivel);
			execute(c, "SET LOCAL lock_timeout='3s'");
			execute(c, "SET LOCAL statement_timeout='5s'");
			return c;
		} catch (SQLException e) {
			c.close();
			throw e;
		}
	}

	private void esperarBloqueo(String bloqueador, String bloqueado) throws Exception {
		var limite = System.nanoTime() + Duration.ofSeconds(2).toNanos();
		try (var monitor = login(RUNTIME)) {
			do {
				if ("t".equals(scalar(monitor, "SELECT " + bloqueador + "=ANY(pg_blocking_pids(" + bloqueado + "))"))) {
					return;
				}
				Thread.sleep(10);
			} while (System.nanoTime() < limite);
		}
		throw new AssertionError("La segunda transacción no esperó a la primera");
	}

	private static String escribirYConfirmar(Connection c, String sql) throws SQLException {
		try {
			assertThat(execute(c, sql)).isOne();
			c.commit();
			return "OK";
		} catch (SQLException e) {
			c.rollback();
			return e.getSQLState();
		}
	}

	private static String literal(UUID id) {
		return "'" + id + "'::uuid";
	}

	private static int execute(Connection c, String sql) throws SQLException {
		try (var s = c.createStatement()) {
			s.execute(sql);
			return s.getUpdateCount();
		}
	}

	private static String scalar(Connection c, String sql) throws SQLException {
		try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
			assertThat(r.next()).isTrue();
			return r.getString(1);
		}
	}
}
