package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.seguridad.SeguridadPropiedades;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

/**
 * Portal de FAMILIA por HTTP (REQ-POR-*, REQ-XC-09): cadena de seguridad completa (MockMvc), servicios y manejador REALES,
 * PostgreSQL 17 descartable y cookies {@code BP_SESION} emitidas por el {@link ServicioTokens} real para usuarios
 * PERSISTIDOS. Cada escenario muta la base con SQL directo entre dos solicitudes con el MISMO token: no hay cache, el
 * cambio se nota en la siguiente solicitud (matriz de JWT viejo con staleness cero). Pool de 3 conexiones.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
@Import(SesionVigenteHttpDbTest.Soporte.class)
class FamiliaPortalHttpDbTest extends BaseDbTest {

	private static final String COOKIE = "BP_SESION";
	private static final String RUTA_MI_FAMILIA = "/api/familia/mi-familia";
	private static final String RUTA_LISTA = "/api/familia/deportistas";
	private static final String NO_ENCONTRADO = "{\"codigo\":\"DEPORTISTA_NO_ENCONTRADO\",\"mensaje\":\"El deportista no existe.\","
			+ "\"detalles\":[]}";

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
	SesionVigenteHttpDbTest.Contador contador;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UUID adminId;
	private UUID fa1;
	private UUID fa2;
	private UUID fb1;
	private UUID usuarioFa1;
	private UUID usuarioFa2;
	private UUID usuarioFb1;
	private UUID dPropio;
	private UUID dInactivo;
	private UUID dAmbas;
	private UUID dDeFa2;
	private UUID dRevocado;
	private UUID dDeB;
	private UUID vinculoPropio;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		contador.baseCaida.set(false);
		escuelaA = datos.escuela("portal-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("portal-b-" + UUID.randomUUID());
		adminId = datos.admin(escuelaA, "admin@portal.example", true);
		UUID adminB = datos.admin(escuelaB, "admin@portal-b.example", true);
		fa1 = datos.familia(escuelaA, "Familia Uno", true);
		fa2 = datos.familia(escuelaA, "Familia Dos", true);
		fb1 = datos.familia(escuelaB, "Familia B", true);
		usuarioFa1 = datos.usuarioFamilia(escuelaA, fa1, "uno@portal.example");
		usuarioFa2 = datos.usuarioFamilia(escuelaA, fa2, "dos@portal.example");
		usuarioFb1 = datos.usuarioFamilia(escuelaB, fb1, "b@portal.example");
		dPropio = datos.deportista(escuelaA, "33000001", "Lola", "Propia", true);
		dInactivo = datos.deportista(escuelaA, "33000002", "Pepe", "Inactivo", false);
		dAmbas = datos.deportista(escuelaA, "33000003", "Mara", "Compartida", true);
		dDeFa2 = datos.deportista(escuelaA, "33000004", "Nico", "Ajeno", true);
		dRevocado = datos.deportista(escuelaA, "33000005", "Rita", "Revocada", true);
		dDeB = datos.deportista(escuelaB, "33000006", "Beto", "DeB", true);
		vinculoPropio = datos.vinculo(escuelaA, fa1, dPropio, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa1, dInactivo, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa1, dAmbas, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa2, dAmbas, "ACTIVO", false, adminId);
		datos.vinculo(escuelaA, fa2, dDeFa2, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa1, dRevocado, "REVOCADO", false, adminId);
		datos.vinculo(escuelaB, fb1, dDeB, "ACTIVO", true, adminB);
		datos.tutor(escuelaA, fa1, "Ana", "Propia");
		datos.tutor(escuelaA, fa2, "Zeta", "Ajena");
	}

	@AfterEach
	void limpiar() {
		contador.baseCaida.set(false);
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private Cookie familia(UUID usuario, UUID escuela, UUID familiaId) {
		return new Cookie(COOKIE, tokens.emitir(usuario, Rol.FAMILIA, escuela, familiaId));
	}

	private Cookie sesionFa1() {
		return familia(usuarioFa1, escuelaA, fa1);
	}

	private Cookie admin() {
		return new Cookie(COOKIE, tokens.emitir(adminId, Rol.ADMIN, escuelaA, null));
	}

	private ResultActions pedir(String ruta, Cookie sesion) throws Exception {
		return mvc.perform(get(ruta).cookie(sesion));
	}

	private MockHttpServletResponse respuesta(String ruta, Cookie sesion) throws Exception {
		return pedir(ruta, sesion).andReturn().getResponse();
	}

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder req) throws Exception {
		Cookie x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return req.cookie(x).header("X-XSRF-TOKEN", x.getValue());
	}

	private static void sesionMuerta(ResultActions r) throws Exception {
		r.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
				.andExpect(cookie().exists(COOKIE)).andExpect(cookie().value(COOKIE, ""))
				.andExpect(cookie().maxAge(COOKIE, 0)).andExpect(cookie().httpOnly(COOKIE, true))
				.andExpect(cookie().path(COOKIE, "/"));
	}

	private long auditoria() {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e").param("e", escuelaA)
				.query(Long.class).single();
	}

	private List<String> rutasDelPortal() {
		return List.of(RUTA_MI_FAMILIA, RUTA_LISTA, RUTA_LISTA + "/" + dPropio);
	}

	// ---------- 200 con los datos propios ----------

	@Test
	void unaFamiliaVeSuFamiliaSusTutoresSusDeportistasYElDetalleDeCadaUno() throws Exception {
		pedir(RUTA_MI_FAMILIA, sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(fa1.toString()))
				.andExpect(jsonPath("$.nombreReferencia").value("Familia Uno"))
				.andExpect(jsonPath("$.tutores.length()").value(1)).andExpect(jsonPath("$.tutores[0].nombre").value("Ana"))
				.andExpect(jsonPath("$.tutores[0].dni").doesNotExist());
		pedir(RUTA_LISTA, sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.totalElementos").value(3))
				.andExpect(jsonPath("$.contenido[?(@.id=='" + dInactivo + "')].activo").value(false))
				.andExpect(jsonPath("$.contenido[?(@.id=='" + dPropio + "')].activo").value(true))
				.andExpect(jsonPath("$.contenido[?(@.id=='" + dDeFa2 + "')]").isEmpty())
				.andExpect(jsonPath("$.contenido[?(@.id=='" + dRevocado + "')]").isEmpty());
		pedir(RUTA_LISTA + "/" + dPropio, sesionFa1()).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(dPropio.toString())).andExpect(jsonPath("$.dni").value("33000001"))
				.andExpect(jsonPath("$.escuelaId").doesNotExist()).andExpect(jsonPath("$.esPrincipal").doesNotExist());
		// El deportista compartido por dos familias lo ven las dos.
		pedir(RUTA_LISTA + "/" + dAmbas, sesionFa1()).andExpect(status().isOk());
		pedir(RUTA_LISTA + "/" + dAmbas, familia(usuarioFa2, escuelaA, fa2)).andExpect(status().isOk());
	}

	@Test
	void losParametrosDeAlcanceDeLaSolicitudNoCambianQuienEs() throws Exception {
		String ajenos = "?familiaId=" + fa2 + "&escuelaId=" + escuelaB + "&usuarioId=" + usuarioFa2;

		pedir(RUTA_MI_FAMILIA + ajenos, sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(fa1.toString()));
		pedir(RUTA_LISTA + ajenos, sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.totalElementos").value(3));
		pedir(RUTA_LISTA + "/" + dDeFa2 + ajenos, sesionFa1()).andExpect(status().isNotFound());
	}

	@Test
	void unIdQueNoEsUuidDa400() throws Exception {
		pedir(RUTA_LISTA + "/no-es-uuid", sesionFa1()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
	}

	// ---------- 404 BYTE a BYTE identicos ----------

	@Test
	void cuatroCausasDeNoEncontradoDanElMismoCuerpoExactoYLosMismosEncabezados() throws Exception {
		datos.estadoVinculo(vinculoPropio, "REVOCADO", adminId);
		List<UUID> noPropios = List.of(dDeFa2 /* otra familia */, dDeB /* otra escuela */, dPropio /* vinculo revocado */,
				dRevocado /* ya revocado */, UUID.randomUUID() /* inexistente */, new UUID(0, 0) /* nulo */);

		byte[] referencia = null;
		for (UUID id : noPropios) {
			MockHttpServletResponse r = respuesta(RUTA_LISTA + "/" + id, sesionFa1());

			assertThat(r.getStatus()).as("id %s", id).isEqualTo(404);
			byte[] cuerpo = r.getContentAsByteArray();
			assertThat(new String(cuerpo, StandardCharsets.UTF_8)).isEqualTo(NO_ENCONTRADO);
			if (referencia == null) {
				referencia = cuerpo;
			}
			assertThat(cuerpo).as("cuerpo byte a byte para %s", id).isEqualTo(referencia);
			assertThat(r.getContentType()).startsWith("application/json");
			assertThat(r.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(h -> h.startsWith(COOKIE + "="));
		}
	}

	// ---------- 401 / 403 ----------

	@Test
	void unAdminEnElPortalDa403YUnaFamiliaEnLaAdministracionDa403() throws Exception {
		for (String ruta : rutasDelPortal()) {
			pedir(ruta, admin()).andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		}
		for (String ruta : List.of("/api/admin/deportistas", "/api/admin/deportistas/" + dPropio,
				"/api/admin/familias/listado", "/api/admin/familias/" + fa1 + "/deportistas")) {
			pedir(ruta, sesionFa1()).andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		}
	}

	@Test
	void sinCookieConCookieManipuladaOExpiradaDa401() throws Exception {
		Cookie manipulada = new Cookie(COOKIE, sesionFa1().getValue() + "x");
		var relojPasado = Clock.fixed(Instant.now().minus(Duration.ofDays(2)), ZoneOffset.UTC);
		Cookie expirada = new Cookie(COOKIE, new ServicioTokens(encoder, propiedades, relojPasado).emitir(usuarioFa1,
				Rol.FAMILIA, escuelaA, fa1));

		for (String ruta : rutasDelPortal()) {
			mvc.perform(get(ruta)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
			pedir(ruta, manipulada).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
			pedir(ruta, expirada).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		}
	}

	// ---------- no hay escritura ----------

	@Test
	void ningunMetodoQueEscribeFuncionaEnElPortalYNoDejaRastroEnLaBase() throws Exception {
		long auditoriaAntes = auditoria();
		long vinculosAntes = jdbc.sql("SELECT count(*) FROM gestion_patin.familia_deportista").query(Long.class).single();

		for (String ruta : rutasDelPortal()) {
			mvc.perform(conCsrf(post(ruta)).cookie(sesionFa1())).andExpect(status().isMethodNotAllowed())
					.andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
			mvc.perform(conCsrf(put(ruta)).cookie(sesionFa1())).andExpect(status().isMethodNotAllowed());
			mvc.perform(conCsrf(patch(ruta)).cookie(sesionFa1())).andExpect(status().isMethodNotAllowed());
			mvc.perform(conCsrf(delete(ruta)).cookie(sesionFa1())).andExpect(status().isMethodNotAllowed());
			mvc.perform(post(ruta).cookie(sesionFa1())).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		}

		assertThat(auditoria()).isEqualTo(auditoriaAntes);
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.familia_deportista").query(Long.class).single())
				.isEqualTo(vinculosAntes);
	}

	@Test
	void lasLecturasDelPortalNoEscribenNingunaFilaDeAuditoria() throws Exception {
		long antes = auditoria();

		for (int i = 0; i < 3; i++) {
			for (String ruta : rutasDelPortal()) {
				pedir(ruta, sesionFa1()).andExpect(status().isOk());
			}
			pedir(RUTA_LISTA + "/" + dDeFa2, sesionFa1()).andExpect(status().isNotFound());
		}

		assertThat(antes).isZero();
		assertThat(auditoria()).as("filas de auditoria tras las lecturas").isEqualTo(antes);
	}

	// ---------- cambios por JDBC: efecto inmediato (sin cache) ----------

	@Test
	void revocarUnVinculoPorJdbcLoOcultaEnLaSiguienteSolicitudDelMismoToken() throws Exception {
		Cookie sesion = sesionFa1();
		pedir(RUTA_LISTA + "/" + dPropio, sesion).andExpect(status().isOk());
		pedir(RUTA_LISTA, sesion).andExpect(jsonPath("$.contenido[?(@.id=='" + dPropio + "')]").isNotEmpty());

		datos.estadoVinculo(vinculoPropio, "REVOCADO", adminId);

		MockHttpServletResponse r = respuesta(RUTA_LISTA + "/" + dPropio, sesion);
		assertThat(r.getStatus()).isEqualTo(404);
		assertThat(r.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(NO_ENCONTRADO);
		pedir(RUTA_LISTA, sesion).andExpect(status().isOk()).andExpect(jsonPath("$.totalElementos").value(2))
				.andExpect(jsonPath("$.contenido[?(@.id=='" + dPropio + "')]").isEmpty());

		datos.estadoVinculo(vinculoPropio, "ACTIVO", adminId);

		pedir(RUTA_LISTA + "/" + dPropio, sesion).andExpect(status().isOk());
	}

	@Test
	void desactivarYReactivarUnDeportistaLoSigueMostrandoConActivoFalseYLuegoTrue() throws Exception {
		Cookie sesion = sesionFa1();

		datos.desactivarDeportista(dPropio);

		pedir(RUTA_LISTA + "/" + dPropio, sesion).andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(false));
		pedir(RUTA_LISTA, sesion).andExpect(jsonPath("$.contenido[?(@.id=='" + dPropio + "')].activo").value(false));

		datos.reactivarDeportista(dPropio);

		pedir(RUTA_LISTA + "/" + dPropio, sesion).andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(true));
	}

	// ---------- N1: familia o usuario inactivos cierran la sesion ----------

	@Test
	void unaFamiliaInactivaCierraLaSesionEnCadaRutaDelPortalYEnMeYAlReactivarlaVuelveSinReloguear() throws Exception {
		Cookie sesion = sesionFa1();
		for (String ruta : rutasDelPortal()) {
			pedir(ruta, sesion).andExpect(status().isOk());
		}

		datos.desactivarFamilia(fa1);

		for (String ruta : rutasDelPortal()) {
			sesionMuerta(pedir(ruta, sesion));
		}
		sesionMuerta(pedir("/api/auth/me", sesion));

		datos.reactivarFamilia(fa1);

		for (String ruta : rutasDelPortal()) {
			pedir(ruta, sesion).andExpect(status().isOk());
		}
		pedir("/api/auth/me", sesion).andExpect(status().isOk());
	}

	@Test
	void unUsuarioInactivoCierraLaSesionYAlReactivarloVuelve() throws Exception {
		Cookie sesion = sesionFa1();
		pedir(RUTA_MI_FAMILIA, sesion).andExpect(status().isOk());

		datos.desactivarUsuario(usuarioFa1);

		for (String ruta : rutasDelPortal()) {
			sesionMuerta(pedir(ruta, sesion));
		}

		datos.reactivarUsuario(usuarioFa1);

		pedir(RUTA_MI_FAMILIA, sesion).andExpect(status().isOk());
	}

	@Test
	void conLaBaseCaidaElPortalDa503SinBorrarLaCookieYAlVolverLaMismaSesionSigueValida() throws Exception {
		Cookie sesion = sesionFa1();
		pedir(RUTA_MI_FAMILIA, sesion).andExpect(status().isOk());

		contador.baseCaida.set(true);

		for (String ruta : rutasDelPortal()) {
			ResultActions r = pedir(ruta, sesion);
			r.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("SERVICIO_NO_DISPONIBLE"))
					.andExpect(cookie().doesNotExist(COOKIE));
			assertThat(r.andReturn().getResponse().getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(h -> h.startsWith(COOKIE + "="));
		}

		contador.baseCaida.set(false);

		pedir(RUTA_MI_FAMILIA, sesion).andExpect(status().isOk());
	}

	// ---------- matriz de JWT viejo (staleness cero) ----------

	@Test
	void conUnTokenViejoCadaCambioDeLaBaseSeNotaEnLaSiguienteSolicitud() throws Exception {
		Cookie viejo = sesionFa1(); // emitido una sola vez, antes de todos los cambios
		pedir(RUTA_LISTA + "/" + dPropio, viejo).andExpect(status().isOk());

		// 1. vinculo revocado: el detalle desaparece de inmediato (404 uniforme) y el listado lo excluye.
		datos.estadoVinculo(vinculoPropio, "REVOCADO", adminId);
		pedir(RUTA_LISTA + "/" + dPropio, viejo).andExpect(status().isNotFound());
		pedir(RUTA_LISTA, viejo).andExpect(jsonPath("$.totalElementos").value(2));
		datos.estadoVinculo(vinculoPropio, "ACTIVO", adminId);
		pedir(RUTA_LISTA + "/" + dPropio, viejo).andExpect(status().isOk());

		// 2. usuario desactivado -> 401 con cookie borrada; reactivado -> 200.
		datos.desactivarUsuario(usuarioFa1);
		sesionMuerta(pedir(RUTA_MI_FAMILIA, viejo));
		datos.reactivarUsuario(usuarioFa1);
		pedir(RUTA_MI_FAMILIA, viejo).andExpect(status().isOk());

		// 3. escuela desactivada -> 401; reactivada -> 200.
		datos.desactivarEscuela(escuelaA);
		sesionMuerta(pedir(RUTA_LISTA, viejo));
		datos.reactivarEscuela(escuelaA);
		pedir(RUTA_LISTA, viejo).andExpect(status().isOk());

		// 4. el usuario pasa a otra familia: el reclamo familia_id del token ya no coincide -> 401.
		jdbc.sql("UPDATE gestion_patin.usuario SET familia_id = :f WHERE id = :u").param("f", fa2).param("u", usuarioFa1)
				.update();
		sesionMuerta(pedir(RUTA_MI_FAMILIA, viejo));
		jdbc.sql("UPDATE gestion_patin.usuario SET familia_id = :f WHERE id = :u").param("f", fa1).param("u", usuarioFa1)
				.update();
		pedir(RUTA_MI_FAMILIA, viejo).andExpect(status().isOk());

		// 5. usuario borrado de la base (sin auditoria que lo referencie) -> 401.
		datos.borrarUsuario(usuarioFb1);
		sesionMuerta(pedir(RUTA_MI_FAMILIA, familia(usuarioFb1, escuelaB, fb1)));
	}

	@Test
	void unTokenDeUnaEscuelaNoSirveConLaFamiliaDeOtra() throws Exception {
		// Token con la familia de A pero la escuela de B (y viceversa): el usuario no existe con esa combinacion -> 401.
		sesionMuerta(pedir(RUTA_LISTA, familia(usuarioFa1, escuelaB, fa1)));
		sesionMuerta(pedir(RUTA_LISTA, familia(usuarioFb1, escuelaA, fb1)));
		sesionMuerta(pedir(RUTA_LISTA, familia(usuarioFa1, escuelaA, fa2)));
	}
}
