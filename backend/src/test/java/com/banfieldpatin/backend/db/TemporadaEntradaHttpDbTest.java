package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entrada del anio de una temporada por HTTP REAL: la cadena de seguridad completa, el controlador, el manejador de errores y
 * la CONFIGURACION REAL de Jackson de la aplicacion (no un mapper de prueba), con PostgreSQL 17 descartable. Reproduce la
 * coercion del campo {@code anio} y fija el contrato: solo se acepta un entero JSON entre 2020 y 2100; un decimal
 * ({@code 2027.9}, {@code 2027.0}, {@code 1e3}), una cadena ({@code "2027"}), un booleano o un entero fuera del rango de
 * {@code int} dan 400 y NO crean ninguna temporada.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
@Import(SesionVigenteHttpDbTest.Soporte.class)
class TemporadaEntradaHttpDbTest extends BaseDbTest {

	private static final String RUTA = "/api/admin/temporadas";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;

	private DatosDb datos;
	private UUID escuela;
	private UUID adminId;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuela = datos.escuela("anio-" + UUID.randomUUID());
		adminId = datos.admin(escuela, "admin@anio.example", true);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuela);
	}

	private MockHttpServletResponse crear(String cuerpo) throws Exception {
		Cookie xsrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		Cookie sesion = new Cookie("BP_SESION", tokens.emitir(adminId, Rol.ADMIN, escuela, null));
		return mvc.perform(post(RUTA).cookie(xsrf, sesion).header("X-XSRF-TOKEN", xsrf.getValue())
				.contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andReturn().getResponse();
	}

	private long temporadas() {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.temporada WHERE escuela_id = :e").param("e", escuela)
				.query(Long.class).single();
	}

	private String codigo(MockHttpServletResponse r) throws Exception {
		return new JsonMapper().readTree(r.getContentAsString()).path("codigo").asString("");
	}

	@Test
	void soloUnEnteroJsonEntre2020Y2100CreaLaTemporadaYTodoLoDemasDa400SinCrearNada() throws Exception {
		// cuerpo -> codigo de error esperado ("" = creada, 201)
		Map<String, String> casos = new LinkedHashMap<>();
		casos.put("{\"anio\":2027.9}", "SOLICITUD_INVALIDA");
		casos.put("{\"anio\":2027.0}", "SOLICITUD_INVALIDA");
		casos.put("{\"anio\":1e3}", "SOLICITUD_INVALIDA");
		casos.put("{\"anio\":2.027e3}", "SOLICITUD_INVALIDA");
		casos.put("{\"anio\":\"2027\"}", "SOLICITUD_INVALIDA");
		casos.put("{\"anio\":\" 2027 \"}", "SOLICITUD_INVALIDA");
		casos.put("{\"anio\":\"2027.9\"}", "SOLICITUD_INVALIDA");
		casos.put("{\"anio\":true}", "SOLICITUD_INVALIDA");
		casos.put("{\"anio\":[2027]}", "SOLICITUD_INVALIDA");
		casos.put("{\"anio\":{\"valor\":2027}}", "SOLICITUD_INVALIDA");
		casos.put("{\"anio\":99999999999}", "SOLICITUD_INVALIDA");
		casos.put("{\"anio\":2019}", "VALIDACION");
		casos.put("{\"anio\":2101}", "VALIDACION");
		casos.put("{\"anio\":-1}", "VALIDACION");
		casos.put("{\"anio\":null}", "VALIDACION");
		casos.put("{}", "VALIDACION");

		List<String> desvios = new ArrayList<>();
		for (Map.Entry<String, String> caso : casos.entrySet()) {
			MockHttpServletResponse r = crear(caso.getKey());
			String obtenido = r.getStatus() == 400 ? codigo(r) : "HTTP " + r.getStatus();
			if (!caso.getValue().equals(obtenido)) {
				desvios.add(caso.getKey() + " -> esperado 400 " + caso.getValue() + " pero fue " + obtenido);
			}
		}

		assertThat(desvios).as("entradas del anio que no se rechazan como corresponde").isEmpty();
		assertThat(temporadas()).as("ninguna entrada invalida debe crear una temporada").isZero();
	}

	@Test
	void unEnteroValidoSigueCreandoLaTemporadaYLosLimitesSeAceptan() throws Exception {
		for (String cuerpo : new String[] { "{\"anio\":2027}", "{ \"anio\" : 2020 }", "{\"anio\":2100}" }) {
			MockHttpServletResponse r = crear(cuerpo);

			assertThat(r.getStatus()).as(cuerpo).isEqualTo(201);
		}
		assertThat(temporadas()).isEqualTo(3);
		assertThat(jdbc.sql("SELECT string_agg(anio::text, ',' ORDER BY anio) FROM gestion_patin.temporada WHERE escuela_id = :e")
				.param("e", escuela).query(String.class).single()).isEqualTo("2020,2027,2100");
	}

	@Test
	void unEnteroRepetidoSigueDando409YElMensajeDeErrorNoHaceEcoDelValorEnviado() throws Exception {
		assertThat(crear("{\"anio\":2027}").getStatus()).isEqualTo(201);

		MockHttpServletResponse repetido = crear("{\"anio\":2027}");
		MockHttpServletResponse decimal = crear("{\"anio\":2027.9}");

		assertThat(repetido.getStatus()).isEqualTo(409);
		assertThat(decimal.getStatus()).isEqualTo(400);
		assertThat(decimal.getContentAsString()).doesNotContain("2027.9");
	}
}
