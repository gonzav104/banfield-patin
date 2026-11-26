package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.banfieldpatin.backend.BackendApplication;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.dto.FamiliaSolicitud;
import com.banfieldpatin.backend.familias.responsables.VinculosTutorConsultaAdmin;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.TemporadaAdminService;
import com.banfieldpatin.backend.temporadas.dto.TemporadaSolicitud;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.familias.tutores.TutorAdminService;

@Tag("db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V15CierresVigenciasDbTest {
	private static final String OWNER = "banfield_schema_owner";
	private static final String MIGRATOR = "banfield_migrator";
	private static final String RUNTIME = "banfield_runtime";
	private static final String AJENO = "banfield_cierre_ajeno";
	private static final String CLAVE = "solo-en-testcontainers";
	private static final String TABLA = "gestion_patin.tutor_deportista";
	private static final String FUNCION = "gestion_patin.cerrar_vigencias_tutor(uuid,uuid,text,uuid,uuid)";
	private static final String LLAMAR = "SELECT * FROM gestion_patin.cerrar_vigencias_tutor(?,?,?,?,?)";

	/** El smoke test arranca la app real, sin controladores sonda del classpath de tests. */
	private static final class ExcluirComponentesDePrueba extends TypeExcludeFilter {
		private final String raiz = V15CierresVigenciasDbTest.class.getProtectionDomain()
				.getCodeSource().getLocation().toExternalForm();

		@Override
		public boolean match(MetadataReader metadata, MetadataReaderFactory factory) throws IOException {
			return metadata.getResource().getURL().toExternalForm().startsWith(raiz);
		}
	}

	private String base;
	private DriverManagerDataSource admin;
	private DriverManagerDataSource runtime;

	@BeforeAll
	void preparar() throws Exception {
		base = PostgresDescartable.crearBase("cierres_runtime");
		admin = base(PostgresDescartable.usuario(), PostgresDescartable.clave());
		runtime = base(RUNTIME, CLAVE);
		try (var c = admin.getConnection(); var s = c.createStatement()) {
			s.execute("CREATE ROLE " + OWNER + " NOLOGIN");
			for (String rol : List.of(MIGRATOR, RUNTIME, AJENO)) {
				s.execute("CREATE ROLE " + rol + " LOGIN PASSWORD '" + CLAVE + "'");
			}
			s.execute(Files.readString(Path.of("ops/postgresql/preparar-migracion.sql")));
		}
		flyway().target("14").load().migrate();
		assertThat(flyway().load().migrate().migrationsExecuted).isEqualTo(1);
		flyway().load().validate();
		try (var c = base(MIGRATOR, CLAVE).getConnection(); var s = c.createStatement()) {
			s.execute("SET ROLE " + OWNER);
			s.execute(Files.readString(Path.of("ops/postgresql/autorizar-runtime-c1.sql")));
			s.execute(Files.readString(Path.of("ops/postgresql/autorizar-runtime-c2a.sql")));
			s.execute("GRANT USAGE ON SCHEMA gestion_patin TO " + AJENO);
		}
	}

	@AfterAll
	void limpiar() throws SQLException {
		if (base != null) {
			PostgresDescartable.eliminarBase(base);
			var global = new DriverManagerDataSource(PostgresDescartable.url(), PostgresDescartable.usuario(),
					PostgresDescartable.clave());
			try (var c = global.getConnection(); var s = c.createStatement()) {
				s.execute("DROP ROLE IF EXISTS " + String.join(", ", AJENO, RUNTIME, MIGRATOR, OWNER));
			}
		}
	}

	@Test
	void backendProdValidaHibernateYOperaVigenciasConElLoginRuntime() {
		var previo = caso();
		try (var contexto = new SpringApplicationBuilder(BackendApplication.class)
				.profiles("test", "prod")
				.initializers(ctx -> ctx.getBeanFactory().registerSingleton("filtroComponentesDePrueba",
						new ExcluirComponentesDePrueba()))
				.run("--server.port=0", "--banfield.frontend.url-base=https://frontend.example.invalid",
						"--spring.datasource.url=" + PostgresDescartable.urlDeBase(base),
						"--spring.datasource.username=" + RUNTIME, "--spring.datasource.password=" + CLAVE)) {
			assertThat(contexto.containsBean("flyway")).isFalse();
			var actual = contexto.getBean(JdbcClient.class).sql("SELECT current_user").query(String.class).single();
			assertThat(actual).isEqualTo(RUNTIME);
			var admin = new UsuarioAutenticado(previo.admin(), previo.escuela(), null, Rol.ADMIN);
			var consulta = contexto.getBean(VinculosTutorConsultaAdmin.class);
			assertThat(consulta.listar(admin, previo.deportista(), false, 0, 20).contenido()).hasSize(1);
			var tutores = contexto.getBean(TutorAdminService.class);
			assertThat(tutores.desactivar(admin, previo.tutor(), 0L, DatosSolicitud.NINGUNA).activo()).isFalse();
			assertThat(tutores.activar(admin, previo.tutor(), 1L, DatosSolicitud.NINGUNA).activo()).isTrue();
			assertThat(consulta.listar(admin, previo.deportista(), false, 0, 20).contenido()).isEmpty();
			assertThat(consulta.listar(admin, previo.deportista(), true, 0, 20).contenido()).hasSize(1);
			assertThat(auditorias(previo)).isEqualTo(1);
			var familias = contexto.getBean(FamiliaAdminService.class);
			var datos = DatosSolicitud.NINGUNA;
			var creada = familias.crear(admin, new FamiliaSolicitud("Familia runtime"), datos);
			var inactiva = familias.desactivar(admin, creada.id(), Long.valueOf(creada.version()), datos);
			assertThat(inactiva.activa()).isFalse();
			assertThat(familias.activar(admin, inactiva.id(), Long.valueOf(inactiva.version()), datos).activa()).isTrue();
			var temporadas = contexto.getBean(TemporadaAdminService.class);
			var temporada = temporadas.crear(admin, new TemporadaSolicitud(2026), datos);
			assertThat(temporadas.activar(admin, temporada.id(), null, datos).activa()).isTrue();
		}
	}

	@Test
	void ejecutaSoloLaFuncionSinEscrituraDirectaNiAccesoAPrivados() throws Exception {
		var caso = caso();
		try (var c = runtime.getConnection(); var s = c.createStatement()) {
			for (String sql : List.of(
					"INSERT INTO " + TABLA + " DEFAULT VALUES",
					"UPDATE " + TABLA + " SET cerrado_en=now()",
					"DELETE FROM " + TABLA,
					"ALTER TABLE " + TABLA + " DISABLE TRIGGER ALL",
					"UPDATE gestion_patin_privado.marca_familia SET estado=false",
					"SET ROLE " + OWNER)) {
				assertThatThrownBy(() -> s.execute(sql)).isInstanceOfSatisfying(SQLException.class,
						e -> assertThat(e.getSQLState()).as(sql).isEqualTo("42501"));
			}
			c.setAutoCommit(false);
			assertThat(cerrar(c, caso.escuela(), caso.admin(), "TUTOR_INACTIVO", caso.tutor(), caso.familia()))
					.containsExactly(caso.vigencia());
			Padre.TUTOR.cambiar(c, caso, false);
			c.commit();
			assertThat(cerrar(c, caso.escuela(), caso.admin(), "TUTOR_INACTIVO", caso.tutor(), caso.familia())).isEmpty();
		}
		assertThat(abierta(caso)).isFalse();
		assertThat(auditorias(caso)).isEqualTo(1);
		try (var c = base(AJENO, CLAVE).getConnection()) {
			assertThatThrownBy(() -> cerrar(c, caso.escuela(), caso.admin(), "TUTOR_INACTIVO",
					caso.tutor(), caso.familia())).isInstanceOfSatisfying(SQLException.class,
							e -> assertThat(e.getSQLState()).isEqualTo("42501"));
		}
		assertThat(JdbcClient.create(admin).sql("""
				SELECT p.prosecdef AND r.rolname='banfield_schema_owner' AND NOT r.rolcanlogin
					AND p.proconfig = ARRAY['search_path=pg_catalog, pg_temp']
				FROM pg_proc p JOIN pg_roles r ON p.proowner=r.oid WHERE p.oid=CAST(:f AS regprocedure)
				""").param("f", FUNCION).query(Boolean.class).single()).isTrue();
	}

	@Test
	void cierreSinBajaFallaAlConfirmarYSinAuditoria() throws Exception {
		var a = caso();
		try (var c = transaccion(runtime)) {
			assertThat(cerrar(c, a.escuela(), a.admin(), Padre.TUTOR.causa, a.tutor(), a.familia()))
					.containsExactly(a.vigencia());
			assertThatThrownBy(c::commit).isInstanceOfSatisfying(SQLException.class,
					e -> assertThat(e.getSQLState()).isEqualTo("23514"));
		}
		assertThat(abierta(a)).isTrue();
		assertThat(auditorias(a)).isZero();
	}

	private int auditorias(Caso caso) {
		return JdbcClient.create(admin).sql("""
				SELECT count(*) FROM gestion_patin.auditoria
				WHERE escuela_id=:escuela AND usuario_id=:actor AND recurso_id=:id
				  AND accion='VIGENCIA_TUTOR_CERRADA' AND recurso_tipo='TUTOR_DEPORTISTA'
				""").param("escuela", caso.escuela()).param("actor", caso.admin()).param("id", caso.vigencia())
				.query(Integer.class).single();
	}

	@Test
	void rechazaActorDeOtraEscuelaYNoCruzaClavesDeEscuelaOFamilia() throws Exception {
		var a = caso();
		var b = caso();
		try (var c = runtime.getConnection()) {
			assertThatThrownBy(() -> cerrar(c, b.escuela(), a.admin(), "TUTOR_INACTIVO", b.tutor(), b.familia()))
					.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
			assertThat(cerrar(c, a.escuela(), a.admin(), "TUTOR_INACTIVO", b.tutor(), b.familia())).isEmpty();
			assertThat(cerrar(c, a.escuela(), a.admin(), "TUTOR_INACTIVO", a.tutor(), b.familia())).isEmpty();
			assertThatThrownBy(() -> cerrar(c, a.escuela(), a.admin(), "REVOCACION_ADMIN", a.tutor(), a.familia()))
					.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("22023"));
		}
		assertThat(abierta(a)).isTrue();
		assertThat(abierta(b)).isTrue();
	}

	@Test
	void elCierreDeUnVinculoSoloAfectaSuParYAdmiteRollback() throws Exception {
		var a = caso();
		var datos = new DatosDb(JdbcClient.create(admin));
		var f2 = datos.familia(a.escuela(), "Otra familia", true);
		datos.vinculo(a.escuela(), f2, a.deportista(), "ACTIVO", false, a.admin());
		var t2 = datos.tutor(a.escuela(), f2, "Otro", "Tutor");
		var b = alta(a.escuela(), a.admin(), f2, a.deportista(), t2);
		try (var c = runtime.getConnection()) {
			c.setAutoCommit(false);
			assertThat(cerrar(c, a.escuela(), a.admin(), "FAMILIA_DEPORTISTA_REVOCADA", a.deportista(), a.familia()))
					.containsExactly(a.vigencia());
			Padre.VINCULO.cambiar(c, a, false);
			c.rollback();
			assertThat(abierta(a)).isTrue();
			assertThat(auditorias(a)).isZero();
			assertThat(cerrar(c, a.escuela(), a.admin(), "DEPORTISTA_INACTIVO", a.deportista(), null))
					.containsExactlyInAnyOrder(a.vigencia(), b.vigencia());
			Padre.DEPORTISTA.cambiar(c, a, false);
			c.commit();
		}
		assertThat(abierta(a)).isFalse();
		assertThat(abierta(b)).isFalse();
		assertThat(auditorias(a)).isEqualTo(1);
		assertThat(auditorias(b)).isEqualTo(1);
	}

	@Test
	void elCierreDeUnaFamiliaNoCierraLasOtrasFamiliasDelDeportista() throws Exception {
		var a = caso();
		var datos = new DatosDb(JdbcClient.create(admin));
		var f2 = datos.familia(a.escuela(), "Otra familia", true);
		datos.vinculo(a.escuela(), f2, a.deportista(), "ACTIVO", false, a.admin());
		var b = alta(a.escuela(), a.admin(), f2, a.deportista(),
				datos.tutor(a.escuela(), f2, "Otro", "Tutor"));
		try (var c = transaccion(runtime)) {
			assertThat(cerrar(c, a.escuela(), a.admin(), "FAMILIA_INACTIVA", a.familia(), null))
					.containsExactly(a.vigencia());
			Padre.FAMILIA.cambiar(c, a, false);
			c.commit();
		}
		assertThat(abierta(a)).isFalse();
		assertThat(abierta(b)).isTrue();
		assertThat(auditorias(a)).isEqualTo(1);
		assertThat(auditorias(b)).isZero();
	}

	private enum Padre {
		FAMILIA("FAMILIA_INACTIVA", "familia", "activa", "false", "true"),
		DEPORTISTA("DEPORTISTA_INACTIVO", "deportista", "activo", "false", "true"),
		TUTOR("TUTOR_INACTIVO", "tutor", "activo", "false", "true"),
		VINCULO("FAMILIA_DEPORTISTA_REVOCADA", "familia_deportista", "estado", "'REVOCADO'", "'ACTIVO'");

		final String causa, tabla, estado, baja, alta;

		Padre(String causa, String tabla, String estado, String baja, String alta) {
			this.causa = causa;
			this.tabla = tabla;
			this.estado = estado;
			this.baja = baja;
			this.alta = alta;
		}

		UUID objetivo(Caso c) {
			return switch (this) {
				case FAMILIA -> c.familia();
				case TUTOR -> c.tutor();
				case DEPORTISTA, VINCULO -> c.deportista();
			};
		}

		UUID contextoFamilia(Caso c) {
			return this == TUTOR || this == VINCULO ? c.familia() : null;
		}

		boolean activo(DriverManagerDataSource ds, Caso c) {
			String condicion = this == VINCULO ? "familia_id=:f AND deportista_id=:id" : "id=:id";
			var consulta = JdbcClient.create(ds).sql("SELECT " + estado + "=" + alta + " FROM gestion_patin."
					+ tabla + " WHERE escuela_id=:e AND " + condicion)
					.param("e", c.escuela()).param("id", objetivo(c));
			if (this == VINCULO) {
				consulta.param("f", c.familia());
			}
			return consulta.query(Boolean.class).single();
		}

		void cambiar(Connection tx, Caso c, boolean activar) throws SQLException {
			String condicion = this == VINCULO ? "familia_id=? AND deportista_id=?" : "id=?";
			try (var s = tx.prepareStatement("UPDATE gestion_patin." + tabla + " SET " + estado
					+ "=" + (activar ? alta : baja) + " WHERE escuela_id=? AND " + condicion)) {
				s.setObject(1, c.escuela());
				s.setObject(2, this == VINCULO ? c.familia() : objetivo(c));
				if (this == VINCULO) {
					s.setObject(3, c.deportista());
				}
				assertThat(s.executeUpdate()).isOne();
			}
		}
	}

	@ParameterizedTest
	@EnumSource(Padre.class)
	void reactivacionEnLaMismaTransaccionSeRechazaInclusoConGuardaAdelantada(Padre padre) throws Exception {
		var a = caso();
		try (var tx = transaccion(runtime); var s = tx.createStatement()) {
			bajar(tx, padre, a);
			s.execute("SET CONSTRAINTS ALL IMMEDIATE");
			exigirRechazoAlReactivar(tx, padre, a);
			// Confirmar una transacción que ya está abortada equivale a rollback en PostgreSQL.
			tx.commit();
		}
		assertThat(padre.activo(admin, a)).isTrue();
		assertThat(abierta(a)).isTrue();
		assertThat(auditorias(a)).isZero();
	}

	@ParameterizedTest
	@EnumSource(Padre.class)
	void diferirOtraVezNoPermiteReactivarYRollbackDelSavepointConservaBajaValida(Padre padre) throws Exception {
		var a = caso();
		try (var tx = transaccion(runtime); var s = tx.createStatement()) {
			var previoAlCierre = tx.setSavepoint();
			bajar(tx, padre, a);
			tx.releaseSavepoint(previoAlCierre);
			s.execute("SET CONSTRAINTS ALL IMMEDIATE");
			s.execute("SET CONSTRAINTS ALL DEFERRED");
			var previoAlRechazo = tx.setSavepoint();
			exigirRechazoAlReactivar(tx, padre, a);
			tx.rollback(previoAlRechazo);
			assertThat(cerrar(tx, a.escuela(), a.admin(), padre.causa, padre.objetivo(a), padre.contextoFamilia(a)))
					.isEmpty();
			padre.cambiar(tx, a, false);
			tx.commit();
		}
		assertThat(padre.activo(admin, a)).isFalse();
		assertThat(abierta(a)).isFalse();
		assertThat(auditorias(a)).isEqualTo(1);

		try (var posterior = transaccion(runtime)) {
			padre.cambiar(posterior, a, true);
			padre.cambiar(posterior, a, true);
			posterior.commit();
		}
		assertThat(padre.activo(admin, a)).isTrue();
		assertThat(abierta(a)).isFalse();
		assertThat(auditorias(a)).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(Padre.class)
	void reactivacionDeOtraSesionEsperaYNoReabre(Padre padre) throws Exception {
		var a = caso();
		try (var e = Executors.newVirtualThreadPerTaskExecutor();
				var baja = transaccion(runtime); var alta = transaccion(runtime)) {
			var pidBaja = pid(baja);
			var pidAlta = pid(alta);
			bajar(baja, padre, a);
			var futuro = e.submit(() -> {
				padre.cambiar(alta, a, true);
				alta.commit();
				return true;
			});
			esperarBloqueo(pidBaja, pidAlta);
			baja.commit();
			assertThat(futuro.get(4, TimeUnit.SECONDS)).isTrue();
		}
		assertThat(padre.activo(admin, a)).isTrue();
		assertThat(abierta(a)).isFalse();
		assertThat(auditorias(a)).isEqualTo(1);
	}

	@Test
	void revertirCierreAlSavepointNoRestringeCambiosDeEstadoSinCierrePropio() throws Exception {
		var a = caso();
		var otra = caso();
		try (var tx = transaccion(runtime); var s = tx.createStatement()) {
			var previoAlCierre = tx.setSavepoint();
			bajar(tx, Padre.TUTOR, a);
			s.execute("SET CONSTRAINTS ALL IMMEDIATE");
			tx.rollback(previoAlCierre);
			// El mismo tutor puede cambiar de estado si el único cierre confirmado corresponde al deportista.
			bajar(tx, Padre.DEPORTISTA, a);
			Padre.TUTOR.cambiar(tx, a, false);
			Padre.TUTOR.cambiar(tx, a, true);
			// No se modifica la vigencia ni la auditoría de otra escuela.
			Padre.TUTOR.cambiar(tx, otra, true);
			tx.commit();
		}
		assertThat(Padre.TUTOR.activo(admin, a)).isTrue();
		assertThat(Padre.DEPORTISTA.activo(admin, a)).isFalse();
		assertThat(abierta(a)).isFalse();
		assertThat(auditorias(a)).isEqualTo(1);
		assertThat(abierta(otra)).isTrue();
		assertThat(auditorias(otra)).isZero();
		assertThat(JdbcClient.create(admin).sql("SELECT causa_cierre FROM " + TABLA + " WHERE id=:id")
				.param("id", a.vigencia()).query(String.class).single()).isEqualTo(Padre.DEPORTISTA.causa);
	}

	@Test
	void sqlDirectoConPermisoNoPuedeFalsearLaEvidenciaDelCierre() throws Exception {
		var a = caso();
		try (var tx = transaccion(admin); var s = tx.createStatement();
				var cierre = tx.prepareStatement("""
						UPDATE gestion_patin.tutor_deportista
						SET cerrado_en=now(), cerrado_por=creado_por, causa_cierre='TUTOR_INACTIVO',
						    cierre_xid=NULL, cierre_tx_inicio=NULL
						WHERE id=?
						""")) {
			cierre.setObject(1, a.vigencia());
			assertThat(cierre.executeUpdate()).isOne();
			Padre.TUTOR.cambiar(tx, a, false);
			s.execute("SET CONSTRAINTS ALL DEFERRED");
			exigirRechazoAlReactivar(tx, Padre.TUTOR, a);
			tx.rollback();
		}
		assertThat(Padre.TUTOR.activo(admin, a)).isTrue();
		assertThat(abierta(a)).isTrue();
		assertThat(auditorias(a)).isZero();
	}

	private static void exigirRechazoAlReactivar(Connection tx, Padre padre, Caso c) {
		assertThatThrownBy(() -> padre.cambiar(tx, c, true)).isInstanceOfSatisfying(SQLException.class,
				ex -> assertThat(ex.getSQLState()).isEqualTo("23514"));
	}

	private void bajar(Connection tx, Padre padre, Caso c) throws SQLException {
		assertThat(cerrar(tx, c.escuela(), c.admin(), padre.causa, padre.objetivo(c), padre.contextoFamilia(c)))
				.containsExactly(c.vigencia());
		padre.cambiar(tx, c, false);
	}

	@ParameterizedTest
	@EnumSource(Padre.class)
	void altaSQLPrimeroLuegoCierreVeElAltaConfirmada(Padre padre) throws Exception {
		var a = caso();
		var b = segundaVigencia(padre, a);
		try (var e = Executors.newVirtualThreadPerTaskExecutor();
				var alta = transaccion(admin); var baja = transaccion(runtime)) {
			var pidAlta = pid(alta);
			var pidBaja = pid(baja);
			var id = insertar(alta, b);
			var futuro = e.submit(() -> {
				var cerradas = cerrar(baja, a.escuela(), a.admin(), padre.causa, padre.objetivo(a), padre.contextoFamilia(a));
				padre.cambiar(baja, a, false);
				baja.commit();
				return cerradas;
			});
			esperarBloqueo(pidAlta, pidBaja);
			alta.commit();
			assertThat(futuro.get(4, TimeUnit.SECONDS)).containsExactlyInAnyOrder(a.vigencia(), id);
		}
		assertThat(abierta(a)).isFalse();
		assertThat(JdbcClient.create(admin).sql("SELECT count(*) FROM " + TABLA
				+ " WHERE escuela_id=:escuela AND cerrado_en IS NULL").param("escuela", a.escuela())
				.query(Integer.class).single()).isZero();
	}

	@ParameterizedTest
	@EnumSource(Padre.class)
	void cierrePrimeroRechazaAltaSQLYReactivacionNoReabre(Padre padre) throws Exception {
		var a = caso();
		var b = segundaVigencia(padre, a);
		try (var e = Executors.newVirtualThreadPerTaskExecutor();
				var baja = transaccion(runtime); var alta = transaccion(admin)) {
			var pidBaja = pid(baja);
			var pidAlta = pid(alta);
			assertThat(cerrar(baja, a.escuela(), a.admin(), padre.causa, padre.objetivo(a), padre.contextoFamilia(a)))
					.containsExactly(a.vigencia());
			padre.cambiar(baja, a, false);
			var futuro = e.submit(() -> {
				try {
					insertar(alta, b);
					alta.commit();
					return "OK";
				} catch (SQLException ex) {
					alta.rollback();
					return ex.getSQLState();
				}
			});
			esperarBloqueo(pidBaja, pidAlta);
			baja.commit();
			assertThat(futuro.get(4, TimeUnit.SECONDS)).isEqualTo("23503");
			padre.cambiar(baja, a, true);
			baja.commit();
		}
		assertThat(abierta(a)).isFalse();
	}

	@Test
	void cierresSuperpuestosSoloCierranUnaVez() throws Exception {
		var a = caso();
		try (var e = Executors.newVirtualThreadPerTaskExecutor();
				var familia = transaccion(runtime); var deportista = transaccion(runtime)) {
			var pidFamilia = pid(familia);
			var pidDeportista = pid(deportista);
			assertThat(cerrar(familia, a.escuela(), a.admin(), Padre.FAMILIA.causa, a.familia(), null))
					.containsExactly(a.vigencia());
			var futuro = e.submit(() -> {
				var ids = cerrar(deportista, a.escuela(), a.admin(), Padre.DEPORTISTA.causa, a.deportista(), null);
				Padre.DEPORTISTA.cambiar(deportista, a, false);
				deportista.commit();
				return ids;
			});
			esperarBloqueo(pidFamilia, pidDeportista);
			Padre.FAMILIA.cambiar(familia, a, false);
			familia.commit();
			assertThat(futuro.get(4, TimeUnit.SECONDS)).isEmpty();
		}
		assertThat(JdbcClient.create(admin).sql("SELECT causa_cierre || ':' || version FROM " + TABLA
				+ " WHERE id=:id").param("id", a.vigencia()).query(String.class).single()).isEqualTo("FAMILIA_INACTIVA:1");
		assertThat(auditorias(a)).isEqualTo(1);
	}

	private Caso segundaVigencia(Padre padre, Caso a) {
		var datos = new DatosDb(JdbcClient.create(admin));
		if (padre == Padre.TUTOR) {
			var d = datos.deportista(a.escuela(), "OTRA", "Otra", "Deportista");
			datos.vinculo(a.escuela(), a.familia(), d, "ACTIVO", false, a.admin());
			return new Caso(a.escuela(), a.admin(), a.familia(), d, a.tutor(), null);
		}
		var t = datos.tutor(a.escuela(), a.familia(), "Segundo", "Tutor");
		return new Caso(a.escuela(), a.admin(), a.familia(), a.deportista(), t, null);
	}

	private UUID insertar(Connection tx, Caso c) throws SQLException {
		try (var s = tx.prepareStatement("""
				INSERT INTO gestion_patin.tutor_deportista
					(escuela_id, familia_id, deportista_id, tutor_id, creado_por, solicitud_id, huella_solicitud)
				VALUES (?, ?, ?, ?, ?, gen_random_uuid(), repeat('a',64))
				RETURNING id
				""")) {
			s.setObject(1, c.escuela());
			s.setObject(2, c.familia());
			s.setObject(3, c.deportista());
			s.setObject(4, c.tutor());
			s.setObject(5, c.admin());
			try (var r = s.executeQuery()) {
				assertThat(r.next()).isTrue();
				return r.getObject(1, UUID.class);
			}
		}
	}

	private static Connection transaccion(DriverManagerDataSource fuente) throws SQLException {
		var c = fuente.getConnection();
		try {
			c.setAutoCommit(false);
			try (var s = c.createStatement()) {
				s.execute("SET LOCAL lock_timeout='3s'");
				s.execute("SET LOCAL statement_timeout='5s'");
			}
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
		long limite = System.nanoTime() + Duration.ofSeconds(2).toNanos();
		try (var c = runtime.getConnection();
				var s = c.prepareStatement("SELECT ?=ANY(pg_blocking_pids(?))")) {
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

	private List<UUID> cerrar(Connection c, UUID escuela, UUID actor, String causa, UUID objetivo, UUID familia)
			throws SQLException {
		try (var s = c.prepareStatement(LLAMAR)) {
			s.setObject(1, escuela);
			s.setObject(2, actor);
			s.setString(3, causa);
			s.setObject(4, objetivo);
			s.setObject(5, familia);
			try (var rs = s.executeQuery()) {
				var ids = new java.util.ArrayList<UUID>();
				while (rs.next()) {
					ids.add(rs.getObject(1, UUID.class));
				}
				return ids;
			}
		}
	}

	private record Caso(UUID escuela, UUID admin, UUID familia, UUID deportista, UUID tutor, UUID vigencia) {}

	private Caso caso() {
		var datos = new DatosDb(JdbcClient.create(admin));
		String clave = UUID.randomUUID().toString();
		var escuela = datos.escuela(clave);
		var actor = datos.admin(escuela, "admin@" + clave + ".invalid", true);
		var familia = datos.familia(escuela, "Familia", true);
		var deportista = datos.deportista(escuela, "PRUEBA", "Deportista", "Prueba");
		datos.vinculo(escuela, familia, deportista, "ACTIVO", false, actor);
		return alta(escuela, actor, familia, deportista, datos.tutor(escuela, familia, "Tutor", "Prueba"));
	}

	private Caso alta(UUID escuela, UUID actor, UUID familia, UUID deportista, UUID tutor) {
		try (var c = admin.getConnection()) {
			var id = insertar(c, new Caso(escuela, actor, familia, deportista, tutor, null));
			return new Caso(escuela, actor, familia, deportista, tutor, id);
		} catch (SQLException ex) {
			throw new AssertionError(ex);
		}
	}

	private boolean abierta(Caso caso) {
		return JdbcClient.create(admin).sql("SELECT cerrado_en IS NULL FROM " + TABLA + " WHERE id=:id")
				.param("id", caso.vigencia()).query(Boolean.class).single();
	}

	private org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
		return Flyway.configure().dataSource(PostgresDescartable.urlDeBase(base), MIGRATOR, CLAVE)
				.initSql("SET ROLE " + OWNER).schemas("gestion_patin").defaultSchema("gestion_patin").cleanDisabled(true);
	}

	private DriverManagerDataSource base(String usuario, String clave) {
		return new DriverManagerDataSource(PostgresDescartable.urlDeBase(base), usuario, clave);
	}
}
