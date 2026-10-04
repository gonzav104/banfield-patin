package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.seguridad.mfa.Base32;
import com.banfieldpatin.backend.seguridad.mfa.Totp;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Flujo real de extremo a extremo: aplicacion completa (cadena de seguridad, controladores, servicios, JPA, Flyway
 * V1 + V2 + V3, auditoria) sobre PostgreSQL 17 con Testcontainers y peticiones MockMvc. El codigo TOTP lo calcula la
 * prueba a partir del secreto que devuelve el enrolamiento, como lo haria la aplicacion autenticadora.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
// El perfil "e2e" excluye los controladores sonda de src/test, que chocan con los reales al escanear toda la app.
@ActiveProfiles({ "test", "e2e" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MfaFlujoCompletoDbTest extends BaseDbTest {

	/** EscuelaActual cachea el id de la escuela configurada: vive toda la clase. */
	private static final String SLUG = "mfa-e2e-" + UUID.randomUUID();
	private static final String PASSWORD = "clave-segura-de-prueba-123";
	private static final String COOKIE = "BP_SESION";

	@DynamicPropertySource
	static void escuelaConfigurada(DynamicPropertyRegistry registro) {
		registro.add("banfield.escuela.slug", () -> SLUG);
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder passwordEncoder;

	private DatosDb datos;
	private UUID escuelaId;

	@BeforeAll
	void crearEscuela() {
		datos = new DatosDb(jdbc);
		escuelaId = datos.escuela(SLUG);
	}

	@AfterAll
	void borrarEscuela() {
		datos.limpiarEscuela(escuelaId);
	}

	// ---------- utilidades HTTP ----------

	private Cookie xsrf() throws Exception {
		Cookie c = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		assertThat(c).isNotNull();
		return c;
	}

	private ResultActions publicar(String ruta, String json, Cookie sesion) throws Exception {
		Cookie x = xsrf();
		MockHttpServletRequestBuilder req = post(ruta).contentType(MediaType.APPLICATION_JSON).content(json).cookie(x)
				.header("X-XSRF-TOKEN", x.getValue());
		if (sesion != null) {
			req.cookie(sesion);
		}
		return mvc.perform(req);
	}

	private ResultActions publicarSinCuerpo(String ruta, Cookie sesion) throws Exception {
		Cookie x = xsrf();
		MockHttpServletRequestBuilder req = post(ruta).cookie(x).header("X-XSRF-TOKEN", x.getValue());
		if (sesion != null) {
			req.cookie(sesion);
		}
		return mvc.perform(req);
	}

	private static Cookie sesionDe(MvcResult r) {
		Cookie c = r.getResponse().getCookie(COOKIE);
		assertThat(c).as("cookie de sesion").isNotNull();
		return new Cookie(COOKIE, c.getValue());
	}

	private static String json(String campo, String valor) {
		return "{\"" + campo + "\":\"" + valor + "\"}";
	}

	private static String credenciales(String email) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
	}

	private UUID crearAdmin(String email) {
		UUID id = datos.admin(escuelaId, email, true);
		jdbc.sql("UPDATE gestion_patin.usuario SET password_hash = :h WHERE id = :id")
				.param("h", passwordEncoder.encode(PASSWORD)).param("id", id).update();
		return id;
	}

	private static String codigoDe(String secretoBase32, int pasosDeDesfase) {
		return Totp.codigo(Base32.decodificar(secretoBase32), Totp.pasoDe(Instant.now()) + pasosDeDesfase);
	}

	private Cookie loginAdmin(String email) throws Exception {
		MvcResult r = publicar("/api/auth/admin/login", credenciales(email), null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mfaPendiente").value(true))
				.andReturn();
		return sesionDe(r);
	}

	/** Enrola y confirma a un ADMIN recien logueado; devuelve el secreto y la cookie de sesion completa. */
	private record Enrolado(String secreto, Cookie sesionCompleta) {
	}

	private Enrolado enrolarYConfirmar(String email) throws Exception {
		Cookie pendiente = loginAdmin(email);
		MvcResult e = publicarSinCuerpo("/api/auth/admin/mfa/enrolar", pendiente).andExpect(status().isOk()).andReturn();
		String secreto = JsonPath.read(e.getResponse().getContentAsString(), "$.secretoBase32");
		MvcResult c = publicar("/api/auth/admin/mfa/confirmar", json("codigo", codigoDe(secreto, 0)), pendiente)
				.andExpect(status().isOk()).andReturn();
		return new Enrolado(secreto, sesionDe(c));
	}

	private boolean mfaHabilitado(UUID usuarioId) {
		return jdbc.sql("SELECT mfa_habilitado FROM gestion_patin.usuario WHERE id = :u").param("u", usuarioId)
				.query(Boolean.class).single();
	}

	private long filasMfa(UUID usuarioId) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.usuario_mfa WHERE usuario_id = :u").param("u", usuarioId)
				.query(Long.class).single();
	}

	// ---------- flujo completo ----------

	@Test
	void flujoCompletoDeLoginPendienteEnrolarConfirmarSesionCompletaYReinicioPorOtroAdmin() throws Exception {
		UUID ana = crearAdmin("ana@e2e.example");
		UUID beto = crearAdmin("beto@e2e.example");

		// 1. La contrasena sola NO da una sesion completa: queda pendiente y no entra a /api/admin/**.
		Cookie pendiente = loginAdmin("ana@e2e.example");
		mvc.perform(get("/api/admin/invitaciones").cookie(pendiente))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		mvc.perform(get("/api/auth/me").cookie(pendiente))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mfaPendiente").value(true))
				.andExpect(jsonPath("$.mfaEnrolado").value(false))
				.andExpect(jsonPath("$.email").value("ana@e2e.example"));

		// 2. Enrolar: secreto y URI una sola vez, sin cache.
		MvcResult enrolado = publicarSinCuerpo("/api/auth/admin/mfa/enrolar", pendiente)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.secretoBase32").value(org.hamcrest.Matchers.matchesPattern("[A-Z2-7]{32}")))
				.andReturn();
		assertThat(enrolado.getResponse().getHeader("Cache-Control")).contains("no-store");
		String secreto = JsonPath.read(enrolado.getResponse().getContentAsString(), "$.secretoBase32");
		String uri = JsonPath.read(enrolado.getResponse().getContentAsString(), "$.otpauthUri");
		assertThat(uri).startsWith("otpauth://totp/Banfield%20Patin:ana%40e2e.example?secret=" + secreto);
		assertThat(mfaHabilitado(ana)).isFalse();

		// 3. Un codigo equivocado no confirma ni da sesion.
		String malo = "%06d".formatted((Integer.parseInt(codigoDe(secreto, 0)) + 13) % 1_000_000);
		MvcResult rechazo = publicar("/api/auth/admin/mfa/confirmar", json("codigo", malo), pendiente)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("CODIGO_MFA_INVALIDO"))
				.andReturn();
		assertThat(rechazo.getResponse().getCookie(COOKIE)).isNull();
		assertThat(mfaHabilitado(ana)).isFalse();

		// 4. El codigo calculado por la "aplicacion autenticadora" confirma y emite la sesion completa.
		MvcResult confirmado = publicar("/api/auth/admin/mfa/confirmar", json("codigo", codigoDe(secreto, 0)), pendiente)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mfaPendiente").value(false))
				.andExpect(jsonPath("$.mfaEnrolado").value(true))
				.andReturn();
		Cookie completa = sesionDe(confirmado);
		assertThat(mfaHabilitado(ana)).isTrue();
		mvc.perform(get("/api/auth/me").cookie(completa))
				.andExpect(status().isOk()).andExpect(jsonPath("$.mfaPendiente").value(false));
		mvc.perform(get("/api/admin/invitaciones").cookie(completa)).andExpect(status().isOk());
		// Con la sesion completa ya no se puede volver a enrolar ni verificar.
		publicarSinCuerpo("/api/auth/admin/mfa/enrolar", completa).andExpect(status().isForbidden());

		// 5. En la base: secreto solo cifrado, confirmado y con el paso usado.
		var fila = jdbc.sql("SELECT secreto_cifrado, confirmado_en, ultimo_paso_usado FROM gestion_patin.usuario_mfa "
				+ "WHERE usuario_id = :u").param("u", ana).query().singleRow();
		assertThat(((byte[]) fila.get("secreto_cifrado"))).hasSize(48);
		assertThat(fila.get("confirmado_en")).isNotNull();
		assertThat(fila.get("ultimo_paso_usado")).isNotNull();

		// 6. Nuevo login: ya enrolada, el token vuelve a ser pendiente; el codigo ya usado se rechaza (anti-repeticion)
		//    y el del paso siguiente (ventana +1) se acepta.
		Cookie pendiente2 = loginAdmin("ana@e2e.example");
		mvc.perform(get("/api/auth/me").cookie(pendiente2)).andExpect(jsonPath("$.mfaEnrolado").value(true));
		// Se usa el paso exacto guardado en la base (no "el actual") para que la prueba no dependa del segundo en curso.
		long pasoUsado = jdbc.sql("SELECT ultimo_paso_usado FROM gestion_patin.usuario_mfa WHERE usuario_id = :u")
				.param("u", ana).query(Long.class).single();
		String repetido = Totp.codigo(Base32.decodificar(secreto), pasoUsado);
		String siguiente = Totp.codigo(Base32.decodificar(secreto), pasoUsado + 1);
		publicar("/api/auth/admin/mfa/verificar", json("codigo", repetido), pendiente2)
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("CODIGO_MFA_INVALIDO"));
		publicar("/api/auth/admin/mfa/verificar", json("codigo", siguiente), pendiente2)
				.andExpect(status().isOk()).andExpect(jsonPath("$.mfaPendiente").value(false));
		// Enrolar de nuevo no es posible ya enrolada (409), con token pendiente.
		Cookie pendiente3 = loginAdmin("ana@e2e.example");
		publicarSinCuerpo("/api/auth/admin/mfa/enrolar", pendiente3).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("MFA_ESTADO_INVALIDO"));

		// 7. Beto (otro ADMIN) tambien se enrola; Ana no puede reiniciar su propio MFA pero si el de Beto.
		Enrolado deBeto = enrolarYConfirmar("beto@e2e.example");
		assertThat(mfaHabilitado(beto)).isTrue();
		publicarSinCuerpo("/api/admin/usuarios/" + ana + "/mfa/reiniciar", completa)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("MFA_AUTOREINICIO_NO_PERMITIDO"));
		assertThat(filasMfa(ana)).isEqualTo(1);
		// Un token pendiente no puede reiniciar a nadie.
		publicarSinCuerpo("/api/admin/usuarios/" + beto + "/mfa/reiniciar", pendiente3).andExpect(status().isForbidden());
		publicarSinCuerpo("/api/admin/usuarios/" + beto + "/mfa/reiniciar", completa).andExpect(status().isNoContent());
		assertThat(filasMfa(beto)).isZero();
		assertThat(mfaHabilitado(beto)).isFalse();

		// 8. Beto debe enrolar de nuevo: verificar sin enrolamiento es 409 y el secreto anterior ya no sirve.
		Cookie pendienteBeto = loginAdmin("beto@e2e.example");
		mvc.perform(get("/api/auth/me").cookie(pendienteBeto)).andExpect(jsonPath("$.mfaEnrolado").value(false));
		publicar("/api/auth/admin/mfa/verificar", json("codigo", codigoDe(deBeto.secreto(), 1)), pendienteBeto)
				.andExpect(status().isConflict());
		MvcResult nuevo = publicarSinCuerpo("/api/auth/admin/mfa/enrolar", pendienteBeto).andExpect(status().isOk()).andReturn();
		String secretoNuevo = JsonPath.read(nuevo.getResponse().getContentAsString(), "$.secretoBase32");
		assertThat(secretoNuevo).isNotEqualTo(deBeto.secreto());
		publicar("/api/auth/admin/mfa/confirmar", json("codigo", codigoDe(secretoNuevo, 0)), pendienteBeto)
				.andExpect(status().isOk());
		assertThat(mfaHabilitado(beto)).isTrue();

		// 9. Auditoria real: eventos MFA_* con actor, sin codigos ni secretos.
		List<String> acciones = jdbc.sql("SELECT accion FROM gestion_patin.auditoria WHERE escuela_id = :e "
				+ "AND accion LIKE 'MFA_%' ORDER BY id").param("e", escuelaId).query(String.class).list();
		assertThat(acciones).contains("MFA_ENROLADO", "MFA_CONFIRMADO", "MFA_FALLO", "MFA_VERIFICADO", "MFA_REINICIADO");
		String volcado = jdbc.sql("SELECT string_agg(detalle::text || coalesce(user_agent, ''), ' ') FROM gestion_patin.auditoria "
				+ "WHERE escuela_id = :e").param("e", escuelaId).query(String.class).single();
		assertThat(volcado).doesNotContain(secreto).doesNotContain(secretoNuevo).doesNotContain(deBeto.secreto())
				.doesNotContain(malo).doesNotContain(PASSWORD);
		var reinicio = jdbc.sql("SELECT usuario_id, recurso_id FROM gestion_patin.auditoria WHERE escuela_id = :e "
				+ "AND accion = 'MFA_REINICIADO'").param("e", escuelaId).query().singleRow();
		assertThat(reinicio.get("usuario_id")).isEqualTo(ana);
		assertThat(reinicio.get("recurso_id")).isEqualTo(beto);
	}

	@Test
	void sinCsrfOSinSesionLasRutasDeMfaRechazan() throws Exception {
		crearAdmin("carla@e2e.example");
		Cookie pendiente = loginAdmin("carla@e2e.example");

		mvc.perform(post("/api/auth/admin/mfa/enrolar").cookie(pendiente))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		publicarSinCuerpo("/api/auth/admin/mfa/enrolar", null)
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		publicar("/api/auth/admin/mfa/verificar", json("codigo", "12345"), pendiente)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACION"));
	}

	@Test
	void familiaNoSeVeAfectadaPorElMfaYNoPuedeUsarSusRutas() throws Exception {
		UUID familiaId = datos.familia(escuelaId, "Los Perez", true);
		UUID madre = datos.usuarioFamilia(escuelaId, familiaId, "madre@e2e.example");
		jdbc.sql("UPDATE gestion_patin.usuario SET password_hash = :h WHERE id = :id")
				.param("h", passwordEncoder.encode(PASSWORD)).param("id", madre).update();

		MvcResult login = publicar("/api/auth/login", credenciales("madre@e2e.example"), null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mfaPendiente").value(false))
				.andReturn();
		Cookie sesion = sesionDe(login);
		mvc.perform(get("/api/auth/me").cookie(sesion)).andExpect(status().isOk())
				.andExpect(jsonPath("$.rol").value("FAMILIA"));
		publicarSinCuerpo("/api/auth/admin/mfa/enrolar", sesion).andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/invitaciones").cookie(sesion)).andExpect(status().isForbidden());
		// Las credenciales de FAMILIA en el login de ADMIN siguen dando el 401 uniforme.
		publicar("/api/auth/admin/login", credenciales("madre@e2e.example"), null)
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"));
	}

	@Test
	void superadoElLimiteDeFallosElCodigoCorrectoTambienSeRechazaConLaMismaRespuesta() throws Exception {
		UUID dani = crearAdmin("dani@e2e.example");
		Enrolado enrolado = enrolarYConfirmar("dani@e2e.example");
		Cookie pendiente = loginAdmin("dani@e2e.example");
		String correcto = codigoDe(enrolado.secreto(), 1);
		String malo = "%06d".formatted((Integer.parseInt(correcto) + 1) % 1_000_000);
		String cuerpoMalo = null;

		for (int i = 0; i < 5; i++) {
			MvcResult r = publicar("/api/auth/admin/mfa/verificar", json("codigo", malo), pendiente)
					.andExpect(status().isUnauthorized()).andReturn();
			cuerpoMalo = r.getResponse().getContentAsString();
		}
		MvcResult bloqueado = publicar("/api/auth/admin/mfa/verificar", json("codigo", correcto), pendiente)
				.andExpect(status().isUnauthorized()).andReturn();

		assertThat(bloqueado.getResponse().getContentAsString()).isEqualTo(cuerpoMalo);
		assertThat(bloqueado.getResponse().getCookie(COOKIE)).isNull();
		assertThat(mfaHabilitado(dani)).isTrue();
	}
}
