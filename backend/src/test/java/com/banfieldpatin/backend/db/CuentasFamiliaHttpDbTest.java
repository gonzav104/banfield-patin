package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.seguridad.SeguridadPropiedades;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.seguridad.VerificadorSesionVigente;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.cuentas.CuentasFamiliaAdminService;

import jakarta.servlet.http.Cookie;

/**
 * U21-BIS C0 por HTTP con la cadena de seguridad completa y PostgreSQL 17: ADMIN lista, desactiva y reactiva las cuentas de
 * una familia. Se prueba el efecto sobre sesiones YA iniciadas (no solo sobre inicios futuros), la independencia de las
 * credenciales de una misma familia, el aislamiento entre familias y escuelas, la idempotencia y la auditoría.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.minimum-idle=1",
		"banfield.cuentas.gestion-habilitada=true" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
@Import(SesionVigenteHttpDbTest.Soporte.class)
class CuentasFamiliaHttpDbTest extends BaseDbTest {

	private static final String COOKIE = "BP_SESION";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	JwtEncoder encoder;
	@Autowired
	SeguridadPropiedades propiedades;
	@Autowired
	CuentasFamiliaAdminService servicio;
	@Autowired
	VerificadorSesionVigente verificador;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UUID adminA;
	private UUID fa1;
	private UUID fa2;
	private UUID fb1;
	private UUID madre;
	private UUID padre;
	private UUID deOtraFamilia;
	private UUID deOtraEscuela;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("cuentas-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("cuentas-b-" + UUID.randomUUID());
		adminA = datos.admin(escuelaA, "admin@cuentas.example", true);
		datos.admin(escuelaB, "admin@cuentas-b.example", true);
		fa1 = datos.familia(escuelaA, "Familia Uno", true);
		fa2 = datos.familia(escuelaA, "Familia Dos", true);
		fb1 = datos.familia(escuelaB, "Familia B", true);
		madre = datos.usuarioFamilia(escuelaA, fa1, "madre@cuentas.example");
		padre = datos.usuarioFamilia(escuelaA, fa1, "padre@cuentas.example");
		deOtraFamilia = datos.usuarioFamilia(escuelaA, fa2, "otra@cuentas.example");
		deOtraEscuela = datos.usuarioFamilia(escuelaB, fb1, "b@cuentas.example");
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	@Test
	void adminVeLasCuentasDeLaFamiliaSinLasDeOtrasNiDatosSensibles() throws Exception {
		mvc.perform(get(ruta(fa1)).cookie(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.gestionHabilitada").value(true))
				.andExpect(jsonPath("$.cuentas.length()").value(2))
				.andExpect(jsonPath("$.cuentas[*].id").value(org.hamcrest.Matchers.containsInAnyOrder(madre.toString(),
						padre.toString())))
				.andExpect(jsonPath("$.cuentas[0].email").exists())
				.andExpect(jsonPath("$.cuentas[0].activa").value(true))
				.andExpect(jsonPath("$.cuentas[0].passwordHash").doesNotExist())
				.andExpect(jsonPath("$.cuentas[0].rol").doesNotExist())
				.andExpect(jsonPath("$.cuentas[0].familiaId").doesNotExist());
	}

	@Test
	void unaFamiliaDeOtraEscuelaOInexistenteEsElMismo404() throws Exception {
		String deOtra = mvc.perform(get(ruta(fb1)).cookie(admin())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("FAMILIA_NO_ENCONTRADA")).andReturn().getResponse()
				.getContentAsString();
		String inexistente = mvc.perform(get(ruta(UUID.randomUUID())).cookie(admin()))
				.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		assertThat(deOtra).isEqualTo(inexistente);
	}

	@Test
	void desactivarCortaLaSesionYaIniciadaSoloDeEsaCuentaYAudita() throws Exception {
		Cookie sesionMadre = familia(madre, fa1);
		Cookie sesionPadre = familia(padre, fa1);
		mvc.perform(get("/api/auth/me").cookie(sesionMadre)).andExpect(status().isOk());
		long antes = auditoria("USUARIO_DESACTIVADO");

		mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/desactivar")).cookie(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(madre.toString()))
				.andExpect(jsonPath("$.activa").value(false));

		// La sesión ya iniciada muere en la siguiente solicitud; la del otro adulto de la familia sigue (credenciales propias).
		sesionMuerta(mvc.perform(get("/api/auth/me").cookie(sesionMadre)));
		sesionMuerta(mvc.perform(get("/api/familia/mi-familia").cookie(sesionMadre)));
		mvc.perform(get("/api/familia/mi-familia").cookie(sesionPadre)).andExpect(status().isOk());
		assertThat(activa(madre)).isFalse();
		assertThat(activa(padre)).isTrue();

		assertThat(auditoria("USUARIO_DESACTIVADO")).isEqualTo(antes + 1);
		var evento = jdbc.sql("""
				SELECT usuario_id, recurso_tipo, recurso_id, detalle::text AS detalle FROM gestion_patin.auditoria
				WHERE escuela_id = :e AND accion = 'USUARIO_DESACTIVADO'
				""").param("e", escuelaA).query().singleRow();
		assertThat(evento.get("usuario_id")).isEqualTo(adminA);
		assertThat(evento.get("recurso_tipo")).isEqualTo("USUARIO");
		assertThat(evento.get("recurso_id")).isEqualTo(madre);
		assertThat((String) evento.get("detalle")).contains(fa1.toString()).doesNotContain("madre@cuentas.example");
	}

	@Test
	void repetirLaAccionDevuelveElEstadoActualSinAuditarDeNuevo() throws Exception {
		for (int i = 0; i < 2; i++) {
			mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/desactivar")).cookie(admin()))
					.andExpect(status().isOk()).andExpect(jsonPath("$.activa").value(false));
		}
		assertThat(auditoria("USUARIO_DESACTIVADO")).isEqualTo(1);
		for (int i = 0; i < 2; i++) {
			mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/reactivar")).cookie(admin()))
					.andExpect(status().isOk()).andExpect(jsonPath("$.activa").value(true));
		}
		assertThat(auditoria("USUARIO_REACTIVADO")).isEqualTo(1);
	}

	@Test
	void reactivarNoReviveLasSesionesAnterioresPeroAdmiteLasNuevas() throws Exception {
		// Sesión iniciada antes de la desactivación (por ejemplo, una cookie robada todavía sin vencer).
		Cookie anterior = familiaEmitidaEn(madre, fa1, Instant.now().minusSeconds(5));
		mvc.perform(get("/api/familia/mi-familia").cookie(anterior)).andExpect(status().isOk());

		mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/desactivar")).cookie(admin())).andExpect(status().isOk());
		mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/reactivar")).cookie(admin()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.activa").value(true));

		sesionMuerta(mvc.perform(get("/api/familia/mi-familia").cookie(anterior)));
		// Un inicio de sesión posterior a la reactivación funciona.
		mvc.perform(get("/api/familia/mi-familia").cookie(familia(madre, fa1))).andExpect(status().isOk());
		assertThat(auditoria("USUARIO_REACTIVADO")).isEqualTo(1);
	}

	/**
	 * Lo que distingue a una sesión anterior a quitar el acceso no es su hora de emisión: aunque su {@code iat} caiga en el
	 * mismo segundo que la reactivación (o después), no revive. Una sesión nueva con la MISMA emisión sí vale.
	 */
	@Test
	void unaSesionAnteriorNoReviveAunqueSuEmisionCoincidaConLaReactivacion() throws Exception {
		Instant mismoSegundo = Instant.now().plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		long anterior = versionSesion(madre);
		mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/desactivar")).cookie(admin())).andExpect(status().isOk());
		mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/reactivar")).cookie(admin())).andExpect(status().isOk());

		sesionMuerta(mvc.perform(get("/api/familia/mi-familia").cookie(familiaEmitidaEn(madre, fa1, mismoSegundo, anterior))));
		mvc.perform(get("/api/familia/mi-familia").cookie(familiaEmitidaEn(madre, fa1, mismoSegundo, versionSesion(madre))))
				.andExpect(status().isOk());
	}

	@Test
	void unTokenDeUnBackendAnteriorSinVersionValeHastaLaPrimeraRevocacionYDespuesNoRevive() throws Exception {
		Cookie legado = familiaEmitidaEn(madre, fa1, Instant.now(), null);
		mvc.perform(get("/api/familia/mi-familia").cookie(legado)).andExpect(status().isOk());
		mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/desactivar")).cookie(admin())).andExpect(status().isOk());
		mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/reactivar")).cookie(admin())).andExpect(status().isOk());
		sesionMuerta(mvc.perform(get("/api/familia/mi-familia").cookie(legado)));
		// Un token sin versión de OTRA cuenta de la familia (nunca revocada) sigue valiendo.
		mvc.perform(get("/api/familia/mi-familia").cookie(familiaEmitidaEn(padre, fa1, Instant.now(), null)))
				.andExpect(status().isOk());
	}

	@Test
	void laVersionCreceSoloAlQuitarElAccesoYEnLaMismaSentenciaQueLaDesactivacion() throws Exception {
		assertThat(versionSesion(madre)).isZero();
		mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/desactivar")).cookie(admin())).andExpect(status().isOk());
		assertThat(versionSesion(madre)).isEqualTo(1);
		mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/desactivar")).cookie(admin())).andExpect(status().isOk());
		assertThat(versionSesion(madre)).as("repetir no cambia la versión").isEqualTo(1);
		mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/reactivar")).cookie(admin())).andExpect(status().isOk());
		mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/reactivar")).cookie(admin())).andExpect(status().isOk());
		assertThat(versionSesion(madre)).as("devolver el acceso no la toca").isEqualTo(1);
		assertThat(versionSesion(padre)).as("otra cuenta de la familia no cambia").isZero();
		assertThat(auditoria("USUARIO_DESACTIVADO")).isEqualTo(1);
		assertThat(auditoria("USUARIO_REACTIVADO")).isEqualTo(1);
	}

	@Test
	void siLaAuditoriaFallaLaDesactivacionYLaRevocacionNoQuedanAplicadas() throws Exception {
		Cookie sesion = familia(madre, fa1);
		jdbc.sql("""
				CREATE FUNCTION gestion_patin.prueba_falla_auditoria() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				  IF NEW.accion = 'USUARIO_DESACTIVADO' THEN RAISE EXCEPTION 'auditoria caida (simulada)'; END IF;
				  RETURN NEW;
				END; $$
				""").update();
		jdbc.sql("CREATE TRIGGER trg_prueba_falla_auditoria BEFORE INSERT ON gestion_patin.auditoria FOR EACH ROW "
				+ "EXECUTE FUNCTION gestion_patin.prueba_falla_auditoria()").update();
		try {
			mvc.perform(conCsrf(post(ruta(fa1) + "/" + madre + "/desactivar")).cookie(admin()))
					.andExpect(status().is5xxServerError());
		} finally {
			jdbc.sql("DROP TRIGGER trg_prueba_falla_auditoria ON gestion_patin.auditoria").update();
			jdbc.sql("DROP FUNCTION gestion_patin.prueba_falla_auditoria()").update();
		}
		assertThat(activa(madre)).isTrue();
		assertThat(versionSesion(madre)).isZero();
		mvc.perform(get("/api/familia/mi-familia").cookie(sesion)).andExpect(status().isOk());
	}

	@Test
	void noSePuedeTocarUnaCuentaDeOtraFamiliaDeOtraEscuelaNiUnAdmin() throws Exception {
		for (UUID ajena : List.of(deOtraFamilia, deOtraEscuela, adminA, UUID.randomUUID())) {
			for (String accion : List.of("desactivar", "reactivar")) {
				mvc.perform(conCsrf(post(ruta(fa1) + "/" + ajena + "/" + accion)).cookie(admin()))
						.andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("CUENTA_NO_ENCONTRADA"));
			}
		}
		mvc.perform(conCsrf(post(ruta(fb1) + "/" + deOtraEscuela + "/desactivar")).cookie(admin()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("FAMILIA_NO_ENCONTRADA"));
		assertThat(activa(deOtraFamilia)).isTrue();
		assertThat(activa(deOtraEscuela)).isTrue();
		assertThat(activa(adminA)).isTrue();
		assertThat(auditoria("USUARIO_DESACTIVADO") + auditoria("USUARIO_REACTIVADO")).isZero();
	}

	@Test
	void unaFamiliaUnAnonimoOUnaSolicitudSinCsrfNoPuedenGestionarCuentas() throws Exception {
		mvc.perform(get(ruta(fa1)).cookie(familia(madre, fa1))).andExpect(status().isForbidden());
		mvc.perform(conCsrf(post(ruta(fa1) + "/" + padre + "/desactivar")).cookie(familia(madre, fa1)))
				.andExpect(status().isForbidden());
		mvc.perform(get(ruta(fa1))).andExpect(status().isUnauthorized());
		mvc.perform(post(ruta(fa1) + "/" + padre + "/desactivar").cookie(admin())).andExpect(status().isForbidden());
		assertThat(activa(padre)).isTrue();
	}

	@Test
	void dosAdminsDesactivandoALaVezDejanUnSoloCambioAuditado() throws Exception {
		UsuarioAutenticado admin = new UsuarioAutenticado(adminA, escuelaA, null, Rol.ADMIN);
		ExecutorService hilos = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch salida = new CountDownLatch(1);
			List<Future<Boolean>> resultados = List.of(1, 2).stream().map(i -> hilos.submit(() -> {
				salida.await(10, TimeUnit.SECONDS);
				return servicio.desactivar(admin, fa1, madre, new DatosSolicitud(null, null)).activa();
			})).toList();
			salida.countDown();
			for (Future<Boolean> r : resultados) {
				assertThat(r.get(30, TimeUnit.SECONDS)).isFalse();
			}
		} finally {
			hilos.shutdownNow();
		}
		assertThat(auditoria("USUARIO_DESACTIVADO")).isEqualTo(1);
		assertThat(versionSesion(madre)).isEqualTo(1);
	}

	@Test
	void quitarYDevolverElAccesoALaVezDejanUnEstadoCoherenteConSusAuditorias() throws Exception {
		UsuarioAutenticado admin = new UsuarioAutenticado(adminA, escuelaA, null, Rol.ADMIN);
		ExecutorService hilos = Executors.newFixedThreadPool(4);
		try {
			for (int ronda = 0; ronda < 10; ronda++) {
				CountDownLatch salida = new CountDownLatch(1);
				List<Future<?>> tareas = new java.util.ArrayList<>();
				for (int i = 0; i < 4; i++) {
					boolean quitar = i % 2 == 0;
					tareas.add(hilos.submit(() -> {
						salida.await(10, TimeUnit.SECONDS);
						return quitar ? servicio.desactivar(admin, fa1, madre, new DatosSolicitud(null, null))
								: servicio.reactivar(admin, fa1, madre, new DatosSolicitud(null, null));
					}));
				}
				salida.countDown();
				for (Future<?> t : tareas) {
					t.get(30, TimeUnit.SECONDS);
				}
			}
		} finally {
			hilos.shutdownNow();
		}
		long quitadas = auditoria("USUARIO_DESACTIVADO");
		long devueltas = auditoria("USUARIO_REACTIVADO");
		// Cada cambio efectivo se audita una vez; la versión crece exactamente una vez por cada vez que se quitó el acceso.
		assertThat(versionSesion(madre)).isEqualTo(quitadas);
		assertThat(quitadas - devueltas).isEqualTo(activa(madre) ? 0 : 1);
		assertThat(versionSesion(padre)).isZero();
	}

	@Test
	void lasInvitacionesSeFiltranPorFamiliaSinMostrarLasDeOtras() throws Exception {
		datos.invitacionPendiente(escuelaA, fa1, adminA, "a".repeat(64));
		datos.invitacionPendiente(escuelaA, fa2, adminA, "b".repeat(64));
		mvc.perform(get("/api/admin/invitaciones").param("familiaId", fa1.toString()).cookie(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.contenido.length()").value(1))
				.andExpect(jsonPath("$.contenido[0].familia.id").value(fa1.toString()))
				.andExpect(jsonPath("$.contenido[0].estado").value("PENDIENTE"));
		mvc.perform(get("/api/admin/invitaciones").param("familiaId", fb1.toString()).cookie(admin()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.contenido.length()").value(0));
		// Sin el filtro, el listado de la escuela sigue igual.
		mvc.perform(get("/api/admin/invitaciones").cookie(admin()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.contenido.length()").value(2));
	}

	private static String ruta(UUID familia) {
		return "/api/admin/familias/" + familia + "/cuentas";
	}

	private Cookie admin() {
		return new Cookie(COOKIE, tokens.emitir(adminA, Rol.ADMIN, escuelaA, null));
	}

	/** Una sesión iniciada ahora: lleva la versión de sesión vigente de la cuenta, como el login real. */
	private Cookie familia(UUID usuario, UUID familiaId) {
		return new Cookie(COOKIE, tokens.emitir(usuario, Rol.FAMILIA, escuelaA, familiaId, versionSesion(usuario)));
	}

	/** Mismo token que emite el servidor, con la emisión en el pasado. */
	private Cookie familiaEmitidaEn(UUID usuario, UUID familiaId, Instant emitido) {
		return familiaEmitidaEn(usuario, familiaId, emitido, null);
	}

	/** Token con emisión y versión de sesión elegidas; {@code version} null = sin el claim (backend anterior). */
	private Cookie familiaEmitidaEn(UUID usuario, UUID familiaId, Instant emitido, Long version) {
		JwtClaimsSet.Builder builder = JwtClaimsSet.builder().issuer(propiedades.jwt().emisor()).subject(usuario.toString())
				.issuedAt(emitido).expiresAt(emitido.plus(propiedades.jwt().duracion())).id(UUID.randomUUID().toString())
				.claim(ServicioTokens.CLAIM_ROL, Rol.FAMILIA.name())
				.claim(ServicioTokens.CLAIM_ESCUELA_ID, escuelaA.toString())
				.claim(ServicioTokens.CLAIM_FAMILIA_ID, familiaId.toString());
		if (version != null) {
			builder.claim("version_sesion", version);
		}
		JwtClaimsSet claims = builder.build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new Cookie(COOKIE, token);
	}

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder req) throws Exception {
		Cookie x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return req.cookie(x).header("X-XSRF-TOKEN", x.getValue());
	}

	private static void sesionMuerta(ResultActions r) throws Exception {
		r.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
				.andExpect(cookie().value(COOKIE, "")).andExpect(cookie().maxAge(COOKIE, 0));
	}

	private long versionSesion(UUID usuario) {
		return jdbc.sql("SELECT version_sesion FROM gestion_patin.usuario WHERE id = :u").param("u", usuario)
				.query(Long.class).single();
	}

	private boolean activa(UUID usuario) {
		return jdbc.sql("SELECT activo FROM gestion_patin.usuario WHERE id = :u").param("u", usuario)
				.query(Boolean.class).single();
	}

	private long auditoria(String accion) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = :a")
				.param("e", escuelaA).param("a", accion).query(Long.class).single();
	}
}
