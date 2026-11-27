package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V18, V19 y V20 con el login runtime real ({@code banfield_runtime}, concesiones de {@code ops/postgresql}): la familia, la escuela y
 * el rol de una cuenta y el autor de un documento no cambian después de creados, de modo que la procedencia de lo ya
 * cargado no puede reescribirse. Lo demás de la cuenta (activo, contraseña, nombre) sigue editable.
 */
@Tag("db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V18PertenenciaUsuarioDbTest {
	private static final String OWNER = "banfield_schema_owner";
	private static final String MIGRATOR = "banfield_migrator";
	private static final String RUNTIME = "banfield_runtime";
	private static final String CLAVE = "solo-en-testcontainers";

	private String base;
	private DriverManagerDataSource admin;
	private JdbcClient runtime;
	private DatosDb datos;
	private UUID escuela;
	private UUID otraEscuela;
	private UUID familia;
	private UUID otraFamilia;
	private UUID usuarioFamilia;
	private UUID usuarioAdmin;
	private UUID documento;

	@BeforeAll
	void preparar() throws Exception {
		base = PostgresDescartable.crearBase("pertenencia_runtime");
		admin = base(PostgresDescartable.usuario(), PostgresDescartable.clave());
		try (var c = admin.getConnection(); var s = c.createStatement()) {
			s.execute("CREATE ROLE " + OWNER + " NOLOGIN");
			for (String rol : List.of(MIGRATOR, RUNTIME)) {
				s.execute("CREATE ROLE " + rol + " LOGIN PASSWORD '" + CLAVE + "'");
			}
			s.execute(Files.readString(Path.of("ops/postgresql/preparar-migracion.sql")));
		}
		// Datos de antes de V18: la migración no los toca.
		flyway().target("17").load().migrate();
		datos = new DatosDb(JdbcClient.create(admin));
		escuela = datos.escuela("pertenencia-" + UUID.randomUUID());
		otraEscuela = datos.escuela("pertenencia-otra-" + UUID.randomUUID());
		familia = datos.familia(escuela, "Familia Uno", true);
		otraFamilia = datos.familia(escuela, "Familia Dos", true);
		usuarioFamilia = datos.usuarioFamilia(escuela, familia, "familia@pertenencia.invalid");
		usuarioAdmin = datos.admin(escuela, "admin@pertenencia.invalid", true);
		UUID deportista = datos.deportista(escuela, "PERT0001", "Deportista", "Sintetico");
		documento = JdbcClient.create(admin).sql("""
				INSERT INTO gestion_patin.documento(escuela_id, deportista_id, tipo, nombre_original, storage_key, mime_type,
				  tamanio_bytes, cargado_por)
				VALUES (:e, :d, 'DNI_DEPORTISTA_FRENTE', 'sintetico.pdf', :k, 'application/pdf', 10, :u) RETURNING id
				""").param("e", escuela).param("d", deportista).param("k", UUID.randomUUID().toString())
				.param("u", usuarioFamilia).query(UUID.class).single();

		// Actualización desde main (V17) hasta el candidato: V18 y V19, aplicadas por el migrador sobre datos existentes.
		assertThat(flyway().target("18").load().migrate().migrationsExecuted).isEqualTo(1);
		assertThat(flyway().target("19").load().migrate().migrationsExecuted).isEqualTo(1);
		assertThat(flyway().target("20").load().migrate().migrationsExecuted).isEqualTo(1);
		flyway().target("20").load().validate();
		try (var c = base(MIGRATOR, CLAVE).getConnection(); var s = c.createStatement()) {
			s.execute("SET ROLE " + OWNER);
			for (String script : List.of("c1", "c2a", "c2b")) {
				s.execute(Files.readString(Path.of("ops/postgresql/autorizar-runtime-" + script + ".sql")));
			}
		}
		runtime = JdbcClient.create(base(RUNTIME, CLAVE));
	}

	@AfterAll
	void limpiar() throws SQLException {
		if (base != null) {
			PostgresDescartable.eliminarBase(base);
			var global = new DriverManagerDataSource(PostgresDescartable.url(), PostgresDescartable.usuario(),
					PostgresDescartable.clave());
			try (var c = global.getConnection(); var s = c.createStatement()) {
				s.execute("DROP ROLE IF EXISTS " + String.join(", ", RUNTIME, MIGRATOR, OWNER));
			}
		}
	}

	@Test
	void laMigracionConservaLosDatosExistentes() {
		assertThat(runtime.sql("SELECT familia_id FROM gestion_patin.usuario WHERE id = :u").param("u", usuarioFamilia)
				.query(UUID.class).single()).isEqualTo(familia);
		assertThat(runtime.sql("SELECT cargado_por FROM gestion_patin.documento WHERE id = :d").param("d", documento)
				.query(UUID.class).single()).isEqualTo(usuarioFamilia);
	}

	@Test
	void v19DejaLasCuentasExistentesEnLaVersionCeroYLaVersionNuncaRetrocede() {
		for (UUID u : List.of(usuarioFamilia, usuarioAdmin)) {
			assertThat(runtime.sql("SELECT version_sesion FROM gestion_patin.usuario WHERE id = :u").param("u", u)
					.query(Long.class).single()).isZero();
		}
		assertThat(runtime.sql("UPDATE gestion_patin.usuario SET activo = false, version_sesion = version_sesion + 1 WHERE id = :u")
				.param("u", usuarioFamilia).update()).isEqualTo(1);
		rechazada("USUARIO_VERSION_SESION_NO_RETROCEDE", () -> runtime.sql(
				"UPDATE gestion_patin.usuario SET version_sesion = version_sesion - 1 WHERE id = :u").param("u", usuarioFamilia)
				.update());
		// Un valor negativo también es un retroceso desde 0 (el CHECK ck_usuario_version_sesion cubre además las altas).
		rechazada("USUARIO_VERSION_SESION_NO_RETROCEDE", () -> runtime.sql(
				"UPDATE gestion_patin.usuario SET version_sesion = -1 WHERE id = :u").param("u", usuarioAdmin).update());
		assertThat(runtime.sql("UPDATE gestion_patin.usuario SET activo = true WHERE id = :u").param("u", usuarioFamilia)
				.update()).isEqualTo(1);
		assertThat(runtime.sql("SELECT version_sesion FROM gestion_patin.usuario WHERE id = :u").param("u", usuarioFamilia)
				.query(Long.class).single()).isEqualTo(1);
	}

	/** V20 con el login runtime: la baja de la familia revoca solo a sus cuentas; la escuela no la puede dar de baja el runtime. */
	@Test
	void v20LaBajaDeLaFamiliaRevocaSusCuentasConElLoginRuntimeYSoloEnLaTransicion() {
		UUID otraFamiliaV20 = datos.familia(escuela, "Otra familia V20", true);
		UUID ajena = datos.usuarioFamilia(escuela, otraFamiliaV20, "ajena-" + UUID.randomUUID() + "@pertenencia.invalid");
		long propia = versionSesion(usuarioFamilia);
		long ajenaAntes = versionSesion(ajena);
		long adminAntes = versionSesion(usuarioAdmin);
		runtime.sql("UPDATE gestion_patin.familia SET activa = false WHERE id = :f").param("f", familia).update();
		assertThat(versionSesion(usuarioFamilia)).isEqualTo(propia + 1);
		assertThat(versionSesion(ajena)).isEqualTo(ajenaAntes);
		assertThat(versionSesion(usuarioAdmin)).isEqualTo(adminAntes);
		runtime.sql("UPDATE gestion_patin.familia SET activa = false WHERE id = :f").param("f", familia).update();
		runtime.sql("UPDATE gestion_patin.familia SET activa = true WHERE id = :f").param("f", familia).update();
		assertThat(versionSesion(usuarioFamilia)).as("reactivar y repetir no incrementan").isEqualTo(propia + 1);
		assertThatThrownBy(() -> runtime.sql("UPDATE gestion_patin.escuela SET activa = false WHERE id = :e")
				.param("e", escuela).update()).rootCause().hasMessageContaining("permission denied");
	}

	private long versionSesion(UUID usuario) {
		return runtime.sql("SELECT version_sesion FROM gestion_patin.usuario WHERE id = :u").param("u", usuario)
				.query(Long.class).single();
	}

	@Test
	void elRuntimeNoPuedeCambiarLaFamiliaDeUnaCuenta() {
		rechazada("USUARIO_PERTENENCIA_INMUTABLE",
				() -> actualizarUsuario("familia_id = :v", otraFamilia, usuarioFamilia));
		assertThat(familiaDe(usuarioFamilia)).isEqualTo(familia);
	}

	@Test
	void elRuntimeNoPuedeCambiarLaEscuelaNiElRolNiElId() {
		rechazada("USUARIO_PERTENENCIA_INMUTABLE",
				() -> actualizarUsuario("escuela_id = :v", otraEscuela, usuarioFamilia));
		// FAMILIA -> ADMIN exige familia NULL; ambos cambios a la vez también se rechazan.
		rechazada("USUARIO_PERTENENCIA_INMUTABLE",
				() -> actualizarUsuario("rol = 'ADMIN', familia_id = :v", null, usuarioFamilia));
		rechazada("USUARIO_PERTENENCIA_INMUTABLE",
				() -> actualizarUsuario("id = :v", UUID.randomUUID(), usuarioFamilia));
		assertThat(runtime.sql("SELECT rol FROM gestion_patin.usuario WHERE id = :u").param("u", usuarioFamilia)
				.query(String.class).single()).isEqualTo("FAMILIA");
	}

	@Test
	void unAdminSinFamiliaNoPuedePasarAUnaFamilia() {
		rechazada("USUARIO_PERTENENCIA_INMUTABLE",
				() -> actualizarUsuario("rol = 'FAMILIA', familia_id = :v", familia, usuarioAdmin));
		assertThat(runtime.sql("SELECT familia_id IS NULL FROM gestion_patin.usuario WHERE id = :u").param("u", usuarioAdmin)
				.query(Boolean.class).single()).isTrue();
	}

	@Test
	void loDemasDeLaCuentaSigueEditableParaFamiliaYParaAdmin() {
		for (UUID u : List.of(usuarioFamilia, usuarioAdmin)) {
			assertThat(runtime.sql("""
					UPDATE gestion_patin.usuario SET activo = false, nombre = 'Otro', password_hash = 'x',
					  ultimo_acceso_en = now() WHERE id = :u
					""").param("u", u).update()).isEqualTo(1);
			assertThat(runtime.sql("UPDATE gestion_patin.usuario SET activo = true WHERE id = :u").param("u", u).update())
					.isEqualTo(1);
		}
		// Reescribir los mismos valores de pertenencia (lo que hace Hibernate al guardar la entidad) no es un cambio.
		assertThat(runtime.sql("UPDATE gestion_patin.usuario SET familia_id = familia_id, rol = rol WHERE id IN (:a, :b)")
				.param("a", usuarioFamilia).param("b", usuarioAdmin).update()).isEqualTo(2);
	}

	@Test
	void elRuntimeNoPuedeCambiarElAutorDeUnDocumentoYLaProcedenciaSeMantiene() {
		UUID otroUsuario = datos.usuarioFamilia(escuela, otraFamilia, "otro-" + UUID.randomUUID() + "@pertenencia.invalid");
		rechazada("DOCUMENTO_PROCEDENCIA_INMUTABLE", () -> runtime.sql(
				"UPDATE gestion_patin.documento SET cargado_por = :v WHERE id = :d").param("v", otroUsuario)
				.param("d", documento).update());
		assertThat(runtime.sql("""
				SELECT u.familia_id FROM gestion_patin.documento d JOIN gestion_patin.usuario u ON u.id = d.cargado_por
				WHERE d.id = :d
				""").param("d", documento).query(UUID.class).single()).isEqualTo(familia);
		// El resto del documento sigue su ciclo normal (por ejemplo, la revisión).
		assertThat(runtime.sql("UPDATE gestion_patin.documento SET estado = 'APROBADO', version = version + 1 WHERE id = :d")
				.param("d", documento).update()).isEqualTo(1);
	}

	private int actualizarUsuario(String asignacion, UUID valor, UUID usuario) {
		return runtime.sql("UPDATE gestion_patin.usuario SET " + asignacion + " WHERE id = :u").param("v", valor)
				.param("u", usuario).update();
	}

	private UUID familiaDe(UUID usuario) {
		return runtime.sql("SELECT familia_id FROM gestion_patin.usuario WHERE id = :u").param("u", usuario)
				.query(UUID.class).single();
	}

	private static void rechazada(String mensaje, Runnable sentencia) {
		assertThatThrownBy(sentencia::run).satisfies(e -> {
			Throwable causa = e;
			while (causa != null && !(causa instanceof SQLException)) {
				causa = causa.getCause();
			}
			assertThat(causa).isInstanceOf(SQLException.class);
			assertThat(((SQLException) causa).getSQLState()).isEqualTo("23514");
			assertThat(causa.getMessage()).contains(mensaje);
		});
	}

	private org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
		return Flyway.configure().dataSource(PostgresDescartable.urlDeBase(base), MIGRATOR, CLAVE)
				.initSql("SET ROLE " + OWNER).schemas("gestion_patin").defaultSchema("gestion_patin").cleanDisabled(true);
	}

	private DriverManagerDataSource base(String usuario, String clave) {
		return new DriverManagerDataSource(PostgresDescartable.urlDeBase(base), usuario, clave);
	}
}
