package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

/**
 * REQ-XC-09 S1-S5, S7-S11 por HTTP: cadena de seguridad completa (MockMvc), PostgreSQL 17 descartable y cookies
 * {@code BP_SESION} emitidas por el {@link ServicioTokens} real. Cada escenario muta la base con SQL directo entre dos
 * solicitudes con el MISMO token: la desactivacion se nota en la siguiente solicitud (sin cache) y la reactivacion
 * tambien. Las rutas sonda ({@code /api/{admin,familia}/sonda-sesion}) no tocan la base, de modo que cuentan solo la
 * sentencia de la revalidacion.
 * <p>
 * La caida de la base se simula en el {@link DataSource} (la conexion falla como cuando el pool no puede responder):
 * se ejercita el verificador real, la traduccion de excepciones de Spring, el proveedor y el manejador de fallo.
 */
@Tag("db")
// Pool minimo: cada contexto cacheado mantiene su pool abierto y el contenedor compartido tiene max_connections=100.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
@Import({ SesionVigenteHttpDbTest.Soporte.class, SesionVigenteHttpDbTest.Sonda.class })
class SesionVigenteHttpDbTest extends BaseDbTest {

	private static final String COOKIE = "BP_SESION";
	private static final String RUTA_ADMIN = "/api/admin/sonda-sesion";
	private static final String RUTA_FAMILIA = "/api/familia/sonda-sesion";

	/** Rutas fuera de la base: solo responden 200 con texto. */
	@RestController
	static class Sonda {

		@GetMapping(RUTA_ADMIN)
		String admin() {
			return "admin";
		}

		@GetMapping(RUTA_FAMILIA)
		String familia() {
			return "familia";
		}
	}

	/** Cuenta las sentencias JDBC y permite simular una caida de la base. */
	static final class Contador {
		final AtomicInteger sentencias = new AtomicInteger();
		final AtomicBoolean baseCaida = new AtomicBoolean();
	}

	@TestConfiguration
	static class Soporte {

		@Bean
		Contador contadorDeSentencias() {
			return new Contador();
		}

		@Bean
		static BeanPostProcessor contarSentenciasDelDataSource(ObjectProvider<Contador> contador) {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String nombre) throws BeansException {
					return bean instanceof DataSource ds && !(bean instanceof DataSourceContador)
							? new DataSourceContador(ds, contador.getObject()) : bean;
				}
			};
		}
	}

	/** DataSource que cuenta {@code prepareStatement/createStatement/prepareCall} y puede fallar al dar conexiones. */
	static final class DataSourceContador extends DelegatingDataSource implements AutoCloseable {

		private static final Set<String> SENTENCIAS = Set.of("prepareStatement", "createStatement", "prepareCall");
		private final Contador contador;

		DataSourceContador(DataSource destino, Contador contador) {
			super(destino);
			this.contador = contador;
		}

		@Override
		public Connection getConnection() throws SQLException {
			if (contador.baseCaida.get()) {
				throw new SQLTransientConnectionException("base de datos caida (simulada)");
			}
			Connection real = super.getConnection();
			return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
					new Class<?>[] { Connection.class }, (proxy, metodo, args) -> {
						if (SENTENCIAS.contains(metodo.getName())) {
							contador.sentencias.incrementAndGet();
						}
						try {
							return metodo.invoke(real, args);
						} catch (InvocationTargetException e) {
							throw e.getCause();
						}
					});
		}

		@Override
		public void close() throws Exception {
			if (getTargetDataSource() instanceof AutoCloseable cierre) {
				cierre.close();
			}
		}
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	Contador contador;

	private DatosDb datos;
	private UUID escuelaId;
	private UUID familiaId;
	private UUID adminId;
	private UUID usuarioFamiliaId;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaId = datos.escuela("sv-http-" + UUID.randomUUID());
		familiaId = datos.familia(escuelaId, "Familia http", true);
		adminId = datos.admin(escuelaId, "admin@http.example", true);
		usuarioFamiliaId = datos.usuarioFamilia(escuelaId, familiaId, "familia@http.example");
		contador.baseCaida.set(false);
	}

	@AfterEach
	void limpiar() {
		contador.baseCaida.set(false);
		datos.limpiarEscuela(escuelaId);
	}

	private Cookie admin() {
		return new Cookie(COOKIE, tokens.emitir(adminId, Rol.ADMIN, escuelaId, null));
	}

	private Cookie familia() {
		return new Cookie(COOKIE, tokens.emitir(usuarioFamiliaId, Rol.FAMILIA, escuelaId, familiaId));
	}

	private Cookie adminPendiente() {
		return new Cookie(COOKIE, tokens.emitirMfaPendiente(adminId, escuelaId));
	}

	private ResultActions pedir(String ruta, Cookie sesion) throws Exception {
		return mvc.perform(get(ruta).cookie(sesion));
	}

	private ResultActions postConCsrf(String ruta, Cookie sesion) throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
		Cookie xsrf = r.getCookie("XSRF-TOKEN");
		assertThat(xsrf).isNotNull();
		return mvc.perform(post(ruta).cookie(xsrf, sesion).header("X-XSRF-TOKEN", xsrf.getValue()));
	}

	private static void sesionMuerta(ResultActions r) throws Exception {
		r.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
				.andExpect(cookie().exists(COOKIE)).andExpect(cookie().value(COOKIE, ""))
				.andExpect(cookie().maxAge(COOKIE, 0)).andExpect(cookie().httpOnly(COOKIE, true))
				.andExpect(cookie().path(COOKIE, "/"));
	}

	private int sentencias(ThrowingRunnable accion) throws Exception {
		contador.sentencias.set(0);
		accion.run();
		return contador.sentencias.get();
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	// ---------- S1, S2, S7: usuario desactivado ----------

	@Test
	void adminDesactivadoPierdeLaSesionEnLaSiguienteSolicitudYAlReactivarloVuelve() throws Exception {
		Cookie sesion = admin();
		pedir(RUTA_ADMIN, sesion).andExpect(status().isOk());

		datos.desactivarUsuario(adminId);

		sesionMuerta(pedir(RUTA_ADMIN, sesion));
		sesionMuerta(pedir("/api/auth/me", sesion));

		datos.reactivarUsuario(adminId);

		pedir(RUTA_ADMIN, sesion).andExpect(status().isOk());
		pedir("/api/auth/me", sesion).andExpect(status().isOk()).andExpect(jsonPath("$.rol").value("ADMIN"));
	}

	@Test
	void familiaDesactivadaComoUsuarioPierdeLaSesionEnRutasDeFamiliaYEnMe() throws Exception {
		Cookie sesion = familia();
		pedir(RUTA_FAMILIA, sesion).andExpect(status().isOk());
		pedir("/api/auth/me", sesion).andExpect(status().isOk()).andExpect(jsonPath("$.rol").value("FAMILIA"));

		datos.desactivarUsuario(usuarioFamiliaId);

		sesionMuerta(pedir(RUTA_FAMILIA, sesion));
		sesionMuerta(pedir("/api/auth/me", sesion));
	}

	@Test
	void usuarioBorradoPierdeLaSesion() throws Exception {
		Cookie sesion = admin();
		pedir(RUTA_ADMIN, sesion).andExpect(status().isOk());

		datos.borrarUsuario(adminId);

		sesionMuerta(pedir(RUTA_ADMIN, sesion));
	}

	// ---------- S8, S11: familia inactiva (a1) ----------

	@Test
	void familiaInactivaCierraLaSesionDeSusUsuariosYAlReactivarlaElMismoTokenVuelveASerValido() throws Exception {
		Cookie sesion = familia();
		pedir(RUTA_FAMILIA, sesion).andExpect(status().isOk());

		datos.desactivarFamilia(familiaId);

		sesionMuerta(pedir(RUTA_FAMILIA, sesion));
		sesionMuerta(pedir("/api/auth/me", sesion));
		// Un ADMIN de la misma escuela no depende de la familia.
		pedir(RUTA_ADMIN, admin()).andExpect(status().isOk());

		datos.reactivarFamilia(familiaId);

		pedir(RUTA_FAMILIA, sesion).andExpect(status().isOk());
		pedir("/api/auth/me", sesion).andExpect(status().isOk());
	}

	// ---------- S4: escuela inactiva ----------

	@Test
	void escuelaInactivaCierraLaSesionDeAdminYFamiliaYAlReactivarlaVuelven() throws Exception {
		Cookie sesionAdmin = admin();
		Cookie sesionFamilia = familia();

		datos.desactivarEscuela(escuelaId);

		sesionMuerta(pedir(RUTA_ADMIN, sesionAdmin));
		sesionMuerta(pedir(RUTA_FAMILIA, sesionFamilia));
		sesionMuerta(pedir("/api/auth/me", sesionAdmin));

		datos.reactivarEscuela(escuelaId);

		pedir(RUTA_ADMIN, sesionAdmin).andExpect(status().isOk());
		pedir(RUTA_FAMILIA, sesionFamilia).andExpect(status().isOk());
	}

	// ---------- S9: coherencia de reclamos ----------

	@Test
	void unTokenCuyoRolOFamiliaNoCoincideConLaBaseNoEsVigente() throws Exception {
		// ADMIN token del usuario que hoy es FAMILIA, y FAMILIA token con la familia de otro.
		Cookie adminDeUnaFamilia = new Cookie(COOKIE, tokens.emitir(usuarioFamiliaId, Rol.ADMIN, escuelaId, null));
		UUID otraFamilia = datos.familia(escuelaId, "Otra familia", true);
		Cookie familiaAjena = new Cookie(COOKIE,
				tokens.emitir(usuarioFamiliaId, Rol.FAMILIA, escuelaId, otraFamilia));
		Cookie familiaDeUnAdmin = new Cookie(COOKIE, tokens.emitir(adminId, Rol.FAMILIA, escuelaId, familiaId));

		sesionMuerta(pedir(RUTA_ADMIN, adminDeUnaFamilia));
		sesionMuerta(pedir(RUTA_FAMILIA, familiaAjena));
		sesionMuerta(pedir(RUTA_FAMILIA, familiaDeUnAdmin));
	}

	// ---------- MFA pendiente ----------

	@Test
	void tokenConMfaPendienteTambienSeRevalida() throws Exception {
		Cookie pendiente = adminPendiente();
		pedir("/api/auth/me", pendiente).andExpect(status().isOk()).andExpect(jsonPath("$.mfaPendiente").value(true));

		datos.desactivarUsuario(adminId);

		sesionMuerta(postConCsrf("/api/auth/admin/mfa/enrolar", pendiente));
		sesionMuerta(pedir("/api/auth/me", pendiente));
	}

	// ---------- logout de una sesion muerta (R3) ----------

	@Test
	void logoutDeUnaSesionMuertaDa401YBorraLaCookie() throws Exception {
		Cookie sesion = familia();
		datos.desactivarFamilia(familiaId);

		sesionMuerta(postConCsrf("/api/auth/logout", sesion));
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'LOGOUT'")
				.param("e", escuelaId).query(Long.class).single()).as("sin evento LOGOUT").isZero();
	}

	// ---------- S10: base no disponible ----------

	@Test
	void conLaBaseCaidaResponde503SinBorrarLaCookieYAlVolverLaSesionSigueValida() throws Exception {
		Cookie sesionAdmin = admin();
		Cookie sesionFamilia = familia();
		pedir(RUTA_ADMIN, sesionAdmin).andExpect(status().isOk());

		contador.baseCaida.set(true);

		for (var r : new ResultActions[] { pedir(RUTA_ADMIN, sesionAdmin), pedir(RUTA_FAMILIA, sesionFamilia),
				pedir("/api/auth/me", sesionAdmin) }) {
			r.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("SERVICIO_NO_DISPONIBLE"))
					.andExpect(cookie().doesNotExist(COOKIE));
			assertThat(r.andReturn().getResponse().getHeaders(HttpHeaders.SET_COOKIE))
					.noneMatch(h -> h.startsWith(COOKIE + "="));
			assertThat(r.andReturn().getResponse().getContentAsString()).doesNotContain("simulada")
					.doesNotContain("Exception").doesNotContain(adminId.toString());
		}

		contador.baseCaida.set(false);

		// La cookie nunca se borro: con la base de vuelta la misma sesion sigue valida.
		pedir(RUTA_ADMIN, sesionAdmin).andExpect(status().isOk());
		pedir(RUTA_FAMILIA, sesionFamilia).andExpect(status().isOk());
	}

	@Test
	void laCaidaDeLaBaseNoAfectaARutasPublicasNiAnonimosQueNoLaConsultan() throws Exception {
		contador.baseCaida.set(true);

		mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());
		mvc.perform(get(RUTA_ADMIN)).andExpect(status().isUnauthorized()).andExpect(cookie().doesNotExist(COOKIE));
	}

	// ---------- costo: una sentencia JDBC por solicitud autenticada ----------

	@Test
	void cadaSolicitudAutenticadaEjecutaExactamenteUnaSentenciaJdbc() throws Exception {
		Cookie sesionAdmin = admin();
		Cookie sesionFamilia = familia();
		Cookie pendiente = adminPendiente();

		assertThat(sentencias(() -> pedir(RUTA_ADMIN, sesionAdmin).andExpect(status().isOk()))).as("ADMIN").isEqualTo(1);
		assertThat(sentencias(() -> pedir(RUTA_FAMILIA, sesionFamilia).andExpect(status().isOk()))).as("FAMILIA")
				.isEqualTo(1);
		assertThat(sentencias(() -> pedir(RUTA_ADMIN, sesionAdmin).andExpect(status().isOk()))).as("sin cache: de nuevo")
				.isEqualTo(1);
		// MFA pendiente: la ruta de /me ademas recarga el usuario con Hibernate, asi que se mide con la sonda de ADMIN
		// (403 por autoridad, pero la revalidacion ya corrio y no hay otra sentencia).
		assertThat(sentencias(() -> pedir(RUTA_ADMIN, pendiente).andExpect(status().isForbidden()))).as("MFA pendiente")
				.isEqualTo(1);
		// Una sesion muerta tambien cuesta una sola sentencia.
		datos.desactivarUsuario(adminId);
		assertThat(sentencias(() -> sesionMuerta(pedir(RUTA_ADMIN, sesionAdmin)))).as("sesion muerta").isEqualTo(1);
	}

	@Test
	void anonimosRutasPublicasYTokensInvalidosNoEjecutanNingunaSentencia() throws Exception {
		Cookie manipulada = new Cookie(COOKIE, admin().getValue() + "x");

		assertThat(sentencias(() -> mvc.perform(get(RUTA_ADMIN)).andExpect(status().isUnauthorized()))).as("anonimo")
				.isZero();
		assertThat(sentencias(() -> mvc.perform(get("/api/auth/csrf").cookie(admin())).andExpect(status().isOk())))
				.as("ruta publica con cookie").isZero();
		assertThat(sentencias(() -> pedir(RUTA_ADMIN, manipulada).andExpect(status().isUnauthorized())))
				.as("firma invalida").isZero();
	}
}
