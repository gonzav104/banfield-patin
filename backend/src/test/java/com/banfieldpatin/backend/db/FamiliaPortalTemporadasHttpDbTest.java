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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

/**
 * Datos deportivos anuales de FAMILIA por HTTP real: cadena de seguridad completa (MockMvc), servicios y manejador REALES,
 * PostgreSQL 17 descartable y cookies {@code BP_SESION} emitidas por el {@link ServicioTokens} real para usuarios PERSISTIDOS.
 * Cada escenario muta la base con SQL directo entre dos solicitudes con el MISMO token: no hay cache y el cambio se nota en la
 * siguiente solicitud (revalidacion central de la sesion).
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
@Import(SesionVigenteHttpDbTest.Soporte.class)
class FamiliaPortalTemporadasHttpDbTest extends BaseDbTest {

	private static final String COOKIE = "BP_SESION";
	private static final String HISTORIAL_404 = "{\"codigo\":\"DEPORTISTA_NO_ENCONTRADO\",\"mensaje\":\"El deportista no existe.\","
			+ "\"detalles\":[]}";
	private static final String DETALLE_404 = "{\"codigo\":\"DEPORTISTA_TEMPORADA_NO_ENCONTRADA\",\"mensaje\":\"El deportista "
			+ "no tiene datos cargados en esa temporada.\",\"detalles\":[]}";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;
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
	private UUID usuarioFb1;
	private UUID t25;
	private UUID t26;
	private UUID t27;
	private UUID tb26;
	private UUID dPropio;
	private UUID dInactivo;
	private UUID dDeFa2;
	private UUID dDeB;
	private UUID vinculoPropio;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		contador.baseCaida.set(false);
		escuelaA = datos.escuela("tportal-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("tportal-b-" + UUID.randomUUID());
		adminId = datos.admin(escuelaA, "admin@tportal.example", true);
		UUID adminB = datos.admin(escuelaB, "admin@tportal-b.example", true);
		fa1 = datos.familia(escuelaA, "Familia Uno", true);
		fa2 = datos.familia(escuelaA, "Familia Dos", true);
		fb1 = datos.familia(escuelaB, "Familia B", true);
		usuarioFa1 = datos.usuarioFamilia(escuelaA, fa1, "uno@tportal.example");
		datos.usuarioFamilia(escuelaA, fa2, "dos@tportal.example");
		usuarioFb1 = datos.usuarioFamilia(escuelaB, fb1, "b@tportal.example");
		t25 = datos.temporada(escuelaA, 2025, false);
		t26 = datos.temporada(escuelaA, 2026, true);
		t27 = datos.temporada(escuelaA, 2027, false);
		tb26 = datos.temporada(escuelaB, 2026, true);
		dPropio = datos.deportista(escuelaA, "36000001", "Lola", "Propia", true);
		dInactivo = datos.deportista(escuelaA, "36000002", "Pepe", "Inactivo", false);
		dDeFa2 = datos.deportista(escuelaA, "36000003", "Nico", "Ajeno", true);
		dDeB = datos.deportista(escuelaB, "36000004", "Beto", "DeB", true);
		vinculoPropio = datos.vinculo(escuelaA, fa1, dPropio, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa1, dInactivo, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa2, dDeFa2, "ACTIVO", true, adminId);
		datos.vinculo(escuelaB, fb1, dDeB, "ACTIVO", true, adminB);
		for (UUID d : List.of(dPropio, dInactivo, dDeFa2)) {
			datos.datosDeportivos(escuelaA, d, t25, "cat25-" + d.toString().substring(0, 8), null, "ACTIVO");
			datos.datosDeportivos(escuelaA, d, t26, "cat26-" + d.toString().substring(0, 8), d.equals(dPropio) ? "007" : null,
					"ACTIVO");
			datos.datosDeportivos(escuelaA, d, t27, "cat27-" + d.toString().substring(0, 8), null, "ACTIVO");
		}
		datos.datosDeportivos(escuelaB, dDeB, tb26, "catB26", null, "ACTIVO");
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

	private String historial(UUID deportista) {
		return "/api/familia/deportistas/" + deportista + "/temporadas";
	}

	private String detalle(UUID deportista, UUID temporada) {
		return historial(deportista) + "/" + temporada;
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

	private List<String> rutasPropias() {
		return List.of(historial(dPropio), detalle(dPropio, t26));
	}

	// ---------- 200 con los datos propios ----------

	@Test
	void unaFamiliaVeElHistorialVisibleYElDetalleDeSuDeportista() throws Exception {
		pedir(historial(dPropio), sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].anio").value(2026)).andExpect(jsonPath("$[0].temporadaActiva").value(true))
				.andExpect(jsonPath("$[0].numeroCorredor").value("007")).andExpect(jsonPath("$[1].anio").value(2025))
				.andExpect(jsonPath("$[0].length()").value(8)).andExpect(jsonPath("$[0].id").doesNotExist())
				.andExpect(jsonPath("$[0].escuelaId").doesNotExist()).andExpect(jsonPath("$[0].deportistaId").doesNotExist())
				.andExpect(jsonPath("$[0].activo").doesNotExist()).andExpect(jsonPath("$[0].dni").doesNotExist());
		pedir(detalle(dPropio, t26), sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.anio").value(2026))
				.andExpect(jsonPath("$.length()").value(8));
		pedir(historial(dInactivo), sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	void losParametrosDeAlcanceDeLaSolicitudNoCambianQuienEs() throws Exception {
		pedir(historial(dDeFa2) + "?familiaId=" + fa2 + "&escuelaId=" + escuelaA + "&usuarioId=" + usuarioFa1, sesionFa1())
				.andExpect(status().isNotFound());
		pedir(historial(dPropio) + "?familiaId=" + fa2, sesionFa1()).andExpect(status().isOk());
	}

	// ---------- 404 uniforme dentro de cada endpoint ----------

	@Test
	void todasLasCausasDeNotFoundDelHistorialDanElMismoCuerpoExactoYLosMismosEncabezados() throws Exception {
		datos.estadoVinculo(vinculoPropio, "REVOCADO", adminId);
		byte[] referencia = null;
		for (UUID id : List.of(dDeFa2 /* otra familia */, dDeB /* otra escuela */, dPropio /* vinculo revocado */,
				UUID.randomUUID() /* inexistente */, new UUID(0, 0))) {
			MockHttpServletResponse r = respuesta(historial(id), sesionFa1());

			assertThat(r.getStatus()).as("id %s", id).isEqualTo(404);
			byte[] cuerpo = r.getContentAsByteArray();
			assertThat(new String(cuerpo, StandardCharsets.UTF_8)).isEqualTo(HISTORIAL_404);
			if (referencia == null) {
				referencia = cuerpo;
			}
			assertThat(cuerpo).as("cuerpo byte a byte para %s", id).isEqualTo(referencia);
			assertThat(r.getContentType()).startsWith("application/json");
			assertThat(r.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(h -> h.startsWith(COOKIE + "="));
		}
	}

	@Test
	void todasLasCausasDeNotFoundDelDetalleDanElMismoCuerpoExactoYLosMismosEncabezados() throws Exception {
		byte[] referencia = null;
		List<String> rutas = List.of(detalle(dDeFa2, t26) /* otra familia */, detalle(dDeB, tb26) /* otra escuela */,
				detalle(dPropio, t27) /* temporada futura (borrador) */, detalle(dPropio, tb26) /* temporada de otra escuela */,
				detalle(dPropio, UUID.randomUUID()) /* temporada inexistente */,
				detalle(UUID.randomUUID(), t26) /* deportista inexistente */, detalle(new UUID(0, 0), new UUID(0, 0)));
		for (String ruta : rutas) {
			MockHttpServletResponse r = respuesta(ruta, sesionFa1());

			assertThat(r.getStatus()).as(ruta).isEqualTo(404);
			byte[] cuerpo = r.getContentAsByteArray();
			assertThat(new String(cuerpo, StandardCharsets.UTF_8)).isEqualTo(DETALLE_404);
			if (referencia == null) {
				referencia = cuerpo;
			}
			assertThat(cuerpo).as("cuerpo byte a byte para %s", ruta).isEqualTo(referencia);
			assertThat(r.getContentType()).startsWith("application/json");
			assertThat(r.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(h -> h.startsWith(COOKIE + "="));
		}
	}

	@Test
	void unUuidMalFormadoDa400SinTocarLaBase() throws Exception {
		pedir("/api/familia/deportistas/no-es-uuid/temporadas", sesionFa1()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		pedir(historial(dPropio) + "/no-es-uuid", sesionFa1()).andExpect(status().isBadRequest());
	}

	// ---------- 401 / 403 ----------

	@Test
	void unAdminDa403YSinCookieOCookieManipuladaDa401() throws Exception {
		Cookie admin = new Cookie(COOKIE, tokens.emitir(adminId, Rol.ADMIN, escuelaA, null));
		for (String ruta : rutasPropias()) {
			pedir(ruta, admin).andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
			mvc.perform(get(ruta)).andExpect(status().isUnauthorized());
			mvc.perform(get(ruta).cookie(new Cookie(COOKIE, "no.es.un.jwt"))).andExpect(status().isUnauthorized());
		}
	}

	@Test
	void ningunMetodoQueEscribeFuncionaYNoDejaRastroEnLaBase() throws Exception {
		long auditoriaAntes = auditoria();
		long filasAntes = jdbc.sql("SELECT count(*) FROM gestion_patin.deportista_temporada").query(Long.class).single();
		Object categoriaAntes = jdbc.sql("SELECT string_agg(categoria, ',' ORDER BY id) FROM gestion_patin.deportista_temporada")
				.query().singleValue();

		for (String ruta : rutasPropias()) {
			mvc.perform(conCsrf(post(ruta)).cookie(sesionFa1())).andExpect(status().isMethodNotAllowed());
			mvc.perform(conCsrf(put(ruta)).cookie(sesionFa1())).andExpect(status().isMethodNotAllowed());
			mvc.perform(conCsrf(patch(ruta)).cookie(sesionFa1())).andExpect(status().isMethodNotAllowed());
			mvc.perform(conCsrf(delete(ruta)).cookie(sesionFa1())).andExpect(status().isMethodNotAllowed());
			mvc.perform(post(ruta).cookie(sesionFa1())).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		}

		assertThat(auditoria()).isEqualTo(auditoriaAntes);
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.deportista_temporada").query(Long.class).single())
				.isEqualTo(filasAntes);
		assertThat(jdbc.sql("SELECT string_agg(categoria, ',' ORDER BY id) FROM gestion_patin.deportista_temporada").query()
				.singleValue()).isEqualTo(categoriaAntes);
	}

	@Test
	void lasLecturasNoEscribenNingunaFilaDeAuditoria() throws Exception {
		long antes = auditoria();

		for (int i = 0; i < 3; i++) {
			for (String ruta : rutasPropias()) {
				pedir(ruta, sesionFa1()).andExpect(status().isOk());
			}
			pedir(historial(dDeFa2), sesionFa1()).andExpect(status().isNotFound());
			pedir(detalle(dPropio, t27), sesionFa1()).andExpect(status().isNotFound());
		}

		assertThat(antes).isZero();
		assertThat(auditoria()).isEqualTo(antes);
	}

	// ---------- cambios por JDBC: efecto inmediato con el mismo token ----------

	@Test
	void revocarElVinculoOcultaElHistorialEnLaSiguienteSolicitudDelMismoToken() throws Exception {
		Cookie sesion = sesionFa1();
		pedir(historial(dPropio), sesion).andExpect(status().isOk());
		pedir(detalle(dPropio, t26), sesion).andExpect(status().isOk());

		datos.estadoVinculo(vinculoPropio, "REVOCADO", adminId);

		assertThat(respuesta(historial(dPropio), sesion).getContentAsString(StandardCharsets.UTF_8)).isEqualTo(HISTORIAL_404);
		assertThat(respuesta(detalle(dPropio, t26), sesion).getContentAsString(StandardCharsets.UTF_8)).isEqualTo(DETALLE_404);

		datos.estadoVinculo(vinculoPropio, "ACTIVO", adminId);
		pedir(historial(dPropio), sesion).andExpect(status().isOk());
	}

	@Test
	void activarOtraTemporadaCambiaLaVisibilidadEnLaSiguienteSolicitudDelMismoToken() throws Exception {
		Cookie sesion = sesionFa1();
		pedir(historial(dPropio), sesion).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
		pedir(detalle(dPropio, t27), sesion).andExpect(status().isNotFound());

		datos.activarTemporada(escuelaA, t27);
		pedir(historial(dPropio), sesion).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].anio").value(2027));
		pedir(detalle(dPropio, t27), sesion).andExpect(status().isOk());

		datos.activarTemporada(escuelaA, t25);
		pedir(historial(dPropio), sesion).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		pedir(detalle(dPropio, t26), sesion).andExpect(status().isNotFound());

		datos.activarTemporada(escuelaA, null);
		pedir(historial(dPropio), sesion).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void desactivarYReactivarAlDeportistaLoSigueMostrandoConSusDatos() throws Exception {
		datos.desactivarDeportista(dPropio);
		pedir(historial(dPropio), sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
		datos.reactivarDeportista(dPropio);
		pedir(historial(dPropio), sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	void unaFamiliaInactivaCierraLaSesionEnLasDosRutasYAlReactivarlaVuelveSinReloguear() throws Exception {
		Cookie sesion = sesionFa1();
		for (String ruta : rutasPropias()) {
			pedir(ruta, sesion).andExpect(status().isOk());
		}

		datos.desactivarFamilia(fa1);

		for (String ruta : rutasPropias()) {
			sesionMuerta(pedir(ruta, sesion));
		}

		datos.reactivarFamilia(fa1);

		for (String ruta : rutasPropias()) {
			pedir(ruta, sesion).andExpect(status().isOk());
		}
	}

	@Test
	void unUsuarioInactivoCierraLaSesionYAlReactivarloVuelve() throws Exception {
		Cookie sesion = sesionFa1();
		datos.desactivarUsuario(usuarioFa1);

		for (String ruta : rutasPropias()) {
			sesionMuerta(pedir(ruta, sesion));
		}

		datos.reactivarUsuario(usuarioFa1);
		pedir(historial(dPropio), sesion).andExpect(status().isOk());
	}

	@Test
	void conLaBaseCaidaDa503SinBorrarLaCookieYAlVolverLaMismaSesionSigueValida() throws Exception {
		Cookie sesion = sesionFa1();
		pedir(historial(dPropio), sesion).andExpect(status().isOk());

		contador.baseCaida.set(true);

		for (String ruta : rutasPropias()) {
			ResultActions r = pedir(ruta, sesion);
			r.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("SERVICIO_NO_DISPONIBLE"))
					.andExpect(cookie().doesNotExist(COOKIE));
		}

		contador.baseCaida.set(false);
		pedir(historial(dPropio), sesion).andExpect(status().isOk());
	}

	@Test
	void unTokenDeUnaEscuelaNoSirveConLaFamiliaDeOtra() throws Exception {
		sesionMuerta(pedir(historial(dPropio), familia(usuarioFa1, escuelaB, fa1)));
		sesionMuerta(pedir(historial(dDeB), familia(usuarioFb1, escuelaA, fb1)));
		sesionMuerta(pedir(historial(dPropio), familia(usuarioFa1, escuelaA, fa2)));
	}
}
