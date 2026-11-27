package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.usuarios.AutenticacionService;
import com.banfieldpatin.backend.usuarios.Rol;

/**
 * Un inicio de sesión que corre a la vez que ADMIN quita el acceso a la cuenta, en los dos órdenes posibles y de forma
 * determinista (la espera se comprueba con {@code pg_blocking_pids}, nunca con pausas). Garantías: el login no puede
 * deshacer la desactivación al guardar la cuenta (pérdida de actualización) y ninguna sesión que haya emitido sobrevive a
 * la revocación, aunque la cuenta se reactive.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.minimum-idle=1" })
@ActiveProfiles({ "test", "e2e" })
class LoginConcurrenteConDesactivacionDbTest extends BaseDbTest {

	private static final String SLUG = "login-concurrente-" + UUID.randomUUID();
	private static final String CLAVE = "clave-familia-de-prueba-789";

	@DynamicPropertySource
	static void configuracion(DynamicPropertyRegistry registro) {
		registro.add("banfield.escuela.slug", () -> SLUG);
	}

	@Autowired
	JdbcClient jdbc;
	@Autowired
	DataSource fuente;
	@Autowired
	AutenticacionService autenticacion;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	com.banfieldpatin.backend.seguridad.VerificadorSesionVigente verificador;

	private DatosDb datos;
	private UUID escuela;
	private UUID usuario;
	private final String email = "concurrente-" + UUID.randomUUID() + "@example.invalid";

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuela = datos.escuelaPorSlugOCrear(SLUG);
		UUID familia = datos.familia(escuela, "Familia Concurrente", true);
		usuario = datos.usuarioFamilia(escuela, familia, email);
		jdbc.sql("UPDATE gestion_patin.usuario SET password_hash = :h WHERE id = :u")
				.param("h", passwordEncoder.encode(CLAVE)).param("u", usuario).update();
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuela);
	}

	@Test
	void elLoginQueCorreDuranteLaDesactivacionNoReactivaLaCuenta() throws Exception {
		ExecutorService hilos = Executors.newSingleThreadExecutor();
		try (Connection desactivacion = fuente.getConnection()) {
			desactivacion.setAutoCommit(false);
			long pid;
			try (var s = desactivacion.createStatement()) {
				var rs = s.executeQuery("SELECT pg_backend_pid()");
				rs.next();
				pid = rs.getLong(1);
			}
			try (var ps = desactivacion.prepareStatement(SentenciasCuenta.DESACTIVAR)) {
				ps.setObject(1, usuario);
				assertThat(ps.executeUpdate()).isEqualTo(1);
			}

			Future<AutenticacionService.SesionIniciada> login = hilos.submit(() -> autenticacion.iniciarSesion(Rol.FAMILIA,
					email, CLAVE, new DatosSolicitud(null, null)));
			esperarBloqueadaPor(pid);
			desactivacion.commit();
			// El login toma la fila de la cuenta FOR SHARE: esperó a la desactivación y, al releer la fila, ya no está activa.
			assertThatThrownBy(() -> login.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
					.cause().isInstanceOfSatisfying(ExcepcionNegocio.class,
							e -> assertThat(e.getCodigo()).isEqualTo("CREDENCIALES_INVALIDAS"));
		} finally {
			hilos.shutdownNow();
		}

		assertThat(jdbc.sql("SELECT activo, version_sesion FROM gestion_patin.usuario WHERE id = :u").param("u", usuario)
				.query((rs, n) -> rs.getBoolean(1) + "/" + rs.getLong(2)).single())
				.as("el login no deshace la desactivación ni la revocación").isEqualTo("false/1");
	}

	/** Si el login tomó la fila primero, la desactivación espera su commit y después revoca la sesión que emitió. */
	@Test
	void unaDesactivacionQueLlegaDuranteElLoginEsperaYRevocaLaSesionEmitida() throws Exception {
		ExecutorService hilos = Executors.newSingleThreadExecutor();
		AutenticacionService.SesionIniciada sesion;
		try (Connection login = fuente.getConnection()) {
			// Simula un login que ya tomó la fila FOR SHARE y todavía no confirmó.
			login.setAutoCommit(false);
			long pid;
			try (var s = login.createStatement()) {
				var rs = s.executeQuery("SELECT pg_backend_pid()");
				rs.next();
				pid = rs.getLong(1);
			}
			long version;
			try (var ps = login.prepareStatement(
					"SELECT version_sesion FROM gestion_patin.usuario WHERE id = ? AND activo FOR SHARE")) {
				ps.setObject(1, usuario);
				var rs = ps.executeQuery();
				assertThat(rs.next()).isTrue();
				version = rs.getLong(1);
			}
			Future<Integer> desactivacion = hilos.submit(() -> jdbc.sql(SentenciasCuenta.DESACTIVAR.replace("?", ":u"))
					.param("u", usuario).update());
			esperarBloqueadaPor(pid);
			login.commit();
			assertThat(desactivacion.get(30, TimeUnit.SECONDS)).isEqualTo(1);
			sesion = new AutenticacionService.SesionIniciada(null, version);
		} finally {
			hilos.shutdownNow();
		}
		UUID familia = jdbc.sql("SELECT familia_id FROM gestion_patin.usuario WHERE id = :u").param("u", usuario)
				.query(UUID.class).single();
		assertThat(verificador.vigente(usuario, escuela, "FAMILIA", familia, sesion.versionSesion())).isFalse();
		jdbc.sql("UPDATE gestion_patin.usuario SET activo = true WHERE id = :u").param("u", usuario).update();
		assertThat(verificador.vigente(usuario, escuela, "FAMILIA", familia, sesion.versionSesion()))
				.as("reactivar no revive la sesión emitida antes").isFalse();
		assertThat(verificador.vigente(usuario, escuela, "FAMILIA", familia, sesion.versionSesion() + 1)).isTrue();
	}

	/** Espera (hasta 10 s) a que exactamente una sesión quede bloqueada por {@code pid}. */
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
		throw new IllegalStateException("se esperaba que el login quedara bloqueado por la desactivación: " + bloqueadas);
	}

	/** La sentencia de desactivación de la cuenta, idéntica a la del servicio de C0. */
	static final class SentenciasCuenta {
		static final String DESACTIVAR = "UPDATE gestion_patin.usuario SET activo = false, version_sesion = version_sesion + 1"
				+ " WHERE id = ? AND activo";
	}
}
