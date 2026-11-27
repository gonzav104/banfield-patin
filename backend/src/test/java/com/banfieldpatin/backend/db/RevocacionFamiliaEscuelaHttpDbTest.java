package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.AutenticacionService;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.cuentas.CuentasFamiliaAdminService;

import jakarta.servlet.http.Cookie;

/**
 * Desactivar una familia o una escuela revoca para siempre las sesiones de las cuentas de su alcance (la versión de sesión
 * crece en la misma transacción); reactivar no las revive. Se mantiene el estado individual de cada cuenta, no se toca
 * nada fuera del alcance ni a los deportistas compartidos, las operaciones sin cambios no incrementan nada y todo es
 * atómico con la auditoría.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=6", "spring.datasource.hikari.minimum-idle=1",
		"banfield.cuentas.gestion-habilitada=true" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
@Import(SesionVigenteHttpDbTest.Soporte.class)
class RevocacionFamiliaEscuelaHttpDbTest extends BaseDbTest {

	private static final String SLUG = "revocacion-" + UUID.randomUUID();
	private static final String CLAVE = "clave-familia-de-prueba-321";
	private static final String COOKIE = "BP_SESION";

	@DynamicPropertySource
	static void configuracion(DynamicPropertyRegistry registro) {
		registro.add("banfield.escuela.slug", () -> SLUG);
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	DataSource fuente;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	FamiliaAdminService familiasAdmin;
	@Autowired
	CuentasFamiliaAdminService cuentasAdmin;
	@Autowired
	AutenticacionService autenticacion;
	@Autowired
	PasswordEncoder passwordEncoder;

	private DatosDb datos;
	private UUID escuela;
	private UUID otraEscuela;
	private UUID admin;
	private UUID adminOtra;
	private UUID fam1;
	private UUID fam2;
	private UUID famOtra;
	private UUID madre;
	private UUID padre;
	private UUID padreInactivo;
	private UUID deOtraFamilia;
	private UUID deOtraEscuela;
	private UUID deportistaCompartido;
	private final String emailMadre = "madre-" + UUID.randomUUID() + "@example.invalid";

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuela = datos.escuelaPorSlugOCrear(SLUG);
		// La escuela vigente es compartida por las pruebas de la clase: parte siempre activa.
		jdbc.sql("UPDATE gestion_patin.escuela SET activa = true WHERE id = :e").param("e", escuela).update();
		otraEscuela = datos.escuela("revocacion-otra-" + UUID.randomUUID());
		admin = datos.admin(escuela, "admin-" + UUID.randomUUID() + "@example.invalid", true);
		adminOtra = datos.admin(otraEscuela, "admin-" + UUID.randomUUID() + "@example.invalid", true);
		fam1 = datos.familia(escuela, "Familia Uno", true);
		fam2 = datos.familia(escuela, "Familia Dos", true);
		famOtra = datos.familia(otraEscuela, "Familia Otra", true);
		madre = datos.usuarioFamilia(escuela, fam1, emailMadre);
		padre = datos.usuarioFamilia(escuela, fam1, "padre-" + UUID.randomUUID() + "@example.invalid");
		padreInactivo = datos.usuarioFamilia(escuela, fam1, "inactivo-" + UUID.randomUUID() + "@example.invalid", false);
		deOtraFamilia = datos.usuarioFamilia(escuela, fam2, "otra-" + UUID.randomUUID() + "@example.invalid");
		deOtraEscuela = datos.usuarioFamilia(otraEscuela, famOtra, "b-" + UUID.randomUUID() + "@example.invalid");
		jdbc.sql("UPDATE gestion_patin.usuario SET password_hash = :h WHERE id = :u")
				.param("h", passwordEncoder.encode(CLAVE)).param("u", madre).update();
		deportistaCompartido = datos.deportista(escuela, "REV" + UUID.randomUUID().toString().substring(0, 8), "Nina", "Compartida");
		datos.vinculo(escuela, fam1, deportistaCompartido, "ACTIVO", true, admin);
		datos.vinculo(escuela, fam2, deportistaCompartido, "ACTIVO", false, admin);
	}

	/** Solo el contenido: la escuela vigente se resuelve por slug y su id queda en caché. */
	@AfterEach
	void limpiar() {
		datos.limpiarContenido(escuela);
		datos.limpiarEscuela(otraEscuela);
	}

	@Test
	void desactivarYReactivarUnaFamiliaRevocaSusSesionesSinTocarLoDemas() throws Exception {
		Cookie sesionMadre = sesion(madre, fam1, escuela);
		Cookie sesionPadre = sesion(padre, fam1, escuela);
		Cookie sesionOtra = sesion(deOtraFamilia, fam2, escuela);
		Cookie sesionB = sesion(deOtraEscuela, famOtra, otraEscuela);
		Cookie sesionAdmin = sesionAdmin(admin, escuela);
		List<Long> otrasAntes = List.of(version(deOtraFamilia), version(deOtraEscuela), version(admin), version(adminOtra));

		cambiarFamilia(fam1, false);
		long auditoriaDesactivar = auditoria("FAMILIA_DESACTIVADA");
		// Reactivar y repetir no revive nada, aunque ocurra todo en el mismo segundo.
		cambiarFamilia(fam1, true);

		mvc.perform(get("/api/familia/mi-familia").cookie(sesionMadre)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/familia/mi-familia").cookie(sesionPadre)).andExpect(status().isUnauthorized());
		// Fuera del alcance: nadie más cambia.
		mvc.perform(get("/api/familia/mi-familia").cookie(sesionOtra)).andExpect(status().isOk());
		mvc.perform(get("/api/familia/mi-familia").cookie(sesionB)).andExpect(status().isOk());
		mvc.perform(get("/api/auth/me").cookie(sesionAdmin)).andExpect(status().isOk());
		assertThat(List.of(version(deOtraFamilia), version(deOtraEscuela), version(admin), version(adminOtra)))
				.isEqualTo(otrasAntes);
		// Nuevo inicio de sesión: válido. Y el estado individual se conserva: la cuenta desactivada sigue desactivada.
		mvc.perform(get("/api/familia/mi-familia").cookie(sesion(madre, fam1, escuela))).andExpect(status().isOk());
		assertThat(activa(padreInactivo)).isFalse();
		assertThat(activa(madre)).isTrue();
		assertThat(version(madre)).isEqualTo(1);
		assertThat(version(padre)).isEqualTo(1);
		assertThat(version(padreInactivo)).isEqualTo(1);
		// El deportista compartido sigue activo y el vínculo de la otra familia, intacto.
		assertThat(jdbc.sql("SELECT activo FROM gestion_patin.deportista WHERE id = :d").param("d", deportistaCompartido)
				.query(Boolean.class).single()).isTrue();
		assertThat(jdbc.sql("SELECT estado FROM gestion_patin.familia_deportista WHERE familia_id = :f AND deportista_id = :d")
				.param("f", fam2).param("d", deportistaCompartido).query(String.class).single()).isEqualTo("ACTIVO");
		assertThat(auditoriaDesactivar).isEqualTo(1);
	}

	@Test
	void desactivarYReactivarUnaEscuelaRevocaLasSesionesDeTodasSusCuentas() throws Exception {
		Cookie sesionMadre = sesion(madre, fam1, escuela);
		Cookie sesionOtra = sesion(deOtraFamilia, fam2, escuela);
		Cookie sesionAdmin = sesionAdmin(admin, escuela);
		Cookie sesionB = sesion(deOtraEscuela, famOtra, otraEscuela);
		long versionB = version(deOtraEscuela);

		datos.desactivarEscuela(escuela);
		datos.reactivarEscuela(escuela);


		mvc.perform(get("/api/familia/mi-familia").cookie(sesionMadre)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/familia/mi-familia").cookie(sesionOtra)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/auth/me").cookie(sesionAdmin)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/familia/mi-familia").cookie(sesionB)).andExpect(status().isOk());
		assertThat(version(deOtraEscuela)).isEqualTo(versionB);
		assertThat(activa(padreInactivo)).isFalse();
		mvc.perform(get("/api/familia/mi-familia").cookie(sesion(madre, fam1, escuela))).andExpect(status().isOk());
	}

	@Test
	void lasOperacionesSinCambiosNoIncrementanVersionesNiDuplicanAuditoria() throws Exception {
		cambiarFamilia(fam1, true); // ya activa
		assertThat(version(madre)).isZero();
		cambiarFamilia(fam1, false);
		long v = version(madre);
		cambiarFamilia(fam1, false); // ya inactiva
		cambiarFamilia(fam1, false);
		assertThat(version(madre)).isEqualTo(v);
		assertThat(auditoria("FAMILIA_DESACTIVADA")).isEqualTo(1);
		datos.desactivarEscuela(escuela);
		long ve = version(madre);
		datos.desactivarEscuela(escuela);
		assertThat(version(madre)).isEqualTo(ve);
		long vOtra = version(deOtraFamilia);
		jdbc.sql("UPDATE gestion_patin.familia SET nombre_referencia = 'Otro nombre' WHERE id = :f").param("f", fam2).update();
		assertThat(version(deOtraFamilia)).as("renombrar no revoca").isEqualTo(vOtra);
	}

	@Test
	void siLaAuditoriaFallaNoQuedaAplicadaLaDesactivacionNiLaRevocacion() throws Exception {
		jdbc.sql("""
				CREATE FUNCTION gestion_patin.prueba_falla_auditoria_familia() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				  IF NEW.accion = 'FAMILIA_DESACTIVADA' THEN RAISE EXCEPTION 'auditoria caida (simulada)'; END IF;
				  RETURN NEW;
				END; $$
				""").update();
		jdbc.sql("CREATE TRIGGER trg_prueba_falla_auditoria_familia BEFORE INSERT ON gestion_patin.auditoria FOR EACH ROW "
				+ "EXECUTE FUNCTION gestion_patin.prueba_falla_auditoria_familia()").update();
		try {
			assertThatThrownBy(() -> cambiarFamilia(fam1, false)).isInstanceOf(RuntimeException.class);
		} finally {
			jdbc.sql("DROP TRIGGER trg_prueba_falla_auditoria_familia ON gestion_patin.auditoria").update();
			jdbc.sql("DROP FUNCTION gestion_patin.prueba_falla_auditoria_familia()").update();
		}
		assertThat(jdbc.sql("SELECT activa FROM gestion_patin.familia WHERE id = :f").param("f", fam1)
				.query(Boolean.class).single()).isTrue();
		assertThat(version(madre)).isZero();
		mvc.perform(get("/api/familia/mi-familia").cookie(sesion(madre, fam1, escuela))).andExpect(status().isOk());
	}

	/**
	 * Un login de una cuenta CREADA después de que la desactivación tomó su foto de cuentas no puede emitir una sesión que
	 * sobreviva: el login toma la fila de la familia y espera la desactivación. Orden determinista con pg_blocking_pids.
	 */
	@Test
	void unLoginDeUnaCuentaNuevaDuranteLaDesactivacionDeSuFamiliaSeRechaza() throws Exception {
		String emailNuevo = "nueva-" + UUID.randomUUID() + "@example.invalid";
		ExecutorService hilos = Executors.newSingleThreadExecutor();
		try (Connection desactivacion = fuente.getConnection()) {
			desactivacion.setAutoCommit(false);
			long pid = pid(desactivacion);
			try (var ps = desactivacion.prepareStatement(
					"UPDATE gestion_patin.familia SET activa = false WHERE id = ?")) {
				ps.setObject(1, fam1);
				assertThat(ps.executeUpdate()).isEqualTo(1);
			}
			// Cuenta nacida y confirmada DESPUÉS de la foto de la desactivación: la revocación masiva no la vio.
			UUID nueva = datos.usuarioFamilia(escuela, fam1, emailNuevo);
			jdbc.sql("UPDATE gestion_patin.usuario SET password_hash = :h WHERE id = :u")
					.param("h", passwordEncoder.encode(CLAVE)).param("u", nueva).update();
			Future<?> login = hilos.submit(() -> autenticacion.iniciarSesion(Rol.FAMILIA, emailNuevo, CLAVE,
					new DatosSolicitud(null, null)));
			esperarBloqueadaPor(pid);
			desactivacion.commit();
			assertThatThrownBy(() -> login.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
					.cause().isInstanceOfSatisfying(ExcepcionNegocio.class,
							e -> assertThat(e.getCodigo()).isEqualTo("CREDENCIALES_INVALIDAS"));
		} finally {
			hilos.shutdownNow();
		}
	}

	/** Desactivaciones colectivas, individuales y logins simultáneos: sin deadlock y con estado coherente. */
	@Test
	void desactivacionesColectivasIndividualesYLoginsSimultaneosNoSeTrabanYQuedanCoherentes() throws Exception {
		UsuarioAutenticado actor = new UsuarioAutenticado(admin, escuela, null, Rol.ADMIN);
		ExecutorService hilos = Executors.newFixedThreadPool(5);
		AtomicInteger errores = new AtomicInteger();
		try {
			for (int ronda = 0; ronda < 8; ronda++) {
				CountDownLatch salida = new CountDownLatch(1);
				List<Future<?>> tareas = new java.util.ArrayList<>();
				tareas.add(hilos.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					cambiarFamiliaComo(actor, fam1, false);
					return null;
				}));
				tareas.add(hilos.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					cuentasAdmin.desactivar(actor, fam1, padre, new DatosSolicitud(null, null));
					return null;
				}));
				tareas.add(hilos.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					cuentasAdmin.reactivar(actor, fam1, padre, new DatosSolicitud(null, null));
					return null;
				}));
				tareas.add(hilos.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						autenticacion.iniciarSesion(Rol.FAMILIA, emailMadre, CLAVE, new DatosSolicitud(null, null));
					} catch (ExcepcionNegocio e) {
						// rechazado por cuenta o familia inactiva: válido
					}
					return null;
				}));
				tareas.add(hilos.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					cambiarFamiliaComo(actor, fam1, true);
					return null;
				}));
				salida.countDown();
				for (Future<?> t : tareas) {
					try {
						t.get(60, TimeUnit.SECONDS);
					} catch (ExecutionException e) {
						if (!(e.getCause() instanceof ExcepcionNegocio)) {
							errores.incrementAndGet();
							throw e;
						}
					}
				}
			}
		} finally {
			hilos.shutdownNow();
		}
		assertThat(errores.get()).as("sin deadlocks ni errores de bloqueo").isZero();
		// La versión de la madre creció exactamente una vez por cada desactivación efectiva de la familia auditada.
		assertThat(version(madre)).isEqualTo(auditoria("FAMILIA_DESACTIVADA"));
		assertThat(activa(padreInactivo)).isFalse();
	}

	private void cambiarFamilia(UUID familia, boolean activar) {
		cambiarFamiliaComo(new UsuarioAutenticado(admin, escuela, null, Rol.ADMIN), familia, activar);
	}

	private void cambiarFamiliaComo(UsuarioAutenticado actor, UUID familia, boolean activar) {
		for (int intento = 0; intento < 5; intento++) {
			long version = jdbc.sql("SELECT version FROM gestion_patin.familia WHERE id = :f").param("f", familia)
					.query(Long.class).single();
			try {
				DatosSolicitud d = new DatosSolicitud(null, null);
				if (activar) {
					familiasAdmin.activar(actor, familia, version, d);
				} else {
					familiasAdmin.desactivar(actor, familia, version, d);
				}
				return;
			} catch (ExcepcionNegocio e) {
				// otra operación cambió la revisión mientras tanto: se relee y se reintenta
				if (!"EDICION_DESACTUALIZADA".equals(e.getCodigo())) {
					throw e;
				}
			} catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
				// ídem: la revisión optimista de JPA detectó el cambio concurrente
			}
		}
		throw new IllegalStateException("la revisión de la familia no se estabilizó");
	}

	private Cookie sesion(UUID usuario, UUID familia, UUID escuelaId) {
		return new Cookie(COOKIE, tokens.emitir(usuario, Rol.FAMILIA, escuelaId, familia, version(usuario)));
	}

	private Cookie sesionAdmin(UUID usuario, UUID escuelaId) {
		return new Cookie(COOKIE, tokens.emitir(usuario, Rol.ADMIN, escuelaId, null, version(usuario)));
	}

	private long version(UUID usuario) {
		return jdbc.sql("SELECT version_sesion FROM gestion_patin.usuario WHERE id = :u").param("u", usuario)
				.query(Long.class).single();
	}

	private boolean activa(UUID usuario) {
		return jdbc.sql("SELECT activo FROM gestion_patin.usuario WHERE id = :u").param("u", usuario)
				.query(Boolean.class).single();
	}

	private long auditoria(String accion) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = :a")
				.param("e", escuela).param("a", accion).query(Long.class).single();
	}

	private static long pid(Connection c) throws Exception {
		try (var s = c.createStatement()) {
			var rs = s.executeQuery("SELECT pg_backend_pid()");
			rs.next();
			return rs.getLong(1);
		}
	}

	private void esperarBloqueadaPor(long pid) throws InterruptedException {
		List<Map<String, Object>> bloqueadas = List.of();
		for (int i = 0; i < 200; i++) {
			bloqueadas = jdbc.sql("""
					SELECT pid, query FROM pg_stat_activity
					WHERE datname = current_database() AND :pid = ANY(pg_blocking_pids(pid))
					""").param("pid", pid).query().listOfRows();
			if (bloqueadas.size() == 1) {
				return;
			}
			Thread.sleep(50);
		}
		throw new IllegalStateException("se esperaba una sesión bloqueada por la desactivación: " + bloqueadas);
	}
}
