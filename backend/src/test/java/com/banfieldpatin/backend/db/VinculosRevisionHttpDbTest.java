package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.usuarios.Rol;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;

/** Sondas HTTP del verificador independiente de U11-A (cadena real, PostgreSQL descartable). */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1",
		"banfield.vinculos.lock-timeout=PT0.3S" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
class VinculosRevisionHttpDbTest extends BaseDbTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UUID adminId;
	private UUID f1;
	private UUID d;
	private UUID inactivo;
	private UUID ajeno;
	private Logger raiz;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("ver-http-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("ver-http-b-" + UUID.randomUUID());
		adminId = datos.admin(escuelaA, "admin@ver.example", true);
		f1 = datos.familia(escuelaA, "Uno", true);
		d = datos.deportista(escuelaA, "42000001", "Nombre", "Apellido");
		inactivo = datos.deportista(escuelaA, "42000002", "Otro", "Inactivo", false);
		ajeno = datos.deportista(escuelaB, "42000003", "Ajeno", "DeB");
		raiz = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		logs = new ListAppender<>();
		logs.start();
		raiz.addAppender(logs);
	}

	@AfterEach
	void limpiar() {
		raiz.detachAppender(logs);
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private MockHttpServletRequestBuilder conSesion(MockHttpServletRequestBuilder req, String json) throws Exception {
		Cookie xsrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		Cookie sesion = new Cookie("BP_SESION", tokens.emitir(adminId, Rol.ADMIN, escuelaA, null));
		var r = req.cookie(xsrf, sesion).header("X-XSRF-TOKEN", xsrf.getValue());
		return json == null ? r : r.contentType("application/json").content(json);
	}

	private MockHttpServletResponse ejecutar(MockHttpServletRequestBuilder req, String json) throws Exception {
		MockHttpServletResponse r = mvc.perform(conSesion(req, json)).andReturn().getResponse();
		r.setCharacterEncoding(StandardCharsets.UTF_8.name());
		return r;
	}

	private String hash() {
		return jdbc.sql("""
				SELECT md5(coalesce((SELECT json_agg(fd ORDER BY fd.id)::text FROM gestion_patin.familia_deportista fd
				                     WHERE fd.escuela_id = :e), '')
				        || coalesce((SELECT json_agg(json_build_array(x.id, x.version, x.version_vinculos) ORDER BY x.id)::text
				                     FROM gestion_patin.deportista x WHERE x.escuela_id = :e), '')
				        || (SELECT count(*) FROM gestion_patin.auditoria a WHERE a.escuela_id = :e)::text)
				""").param("e", escuelaA).query(String.class).single();
	}

	@Test
	void laLecturaDaUn404ByteIdenticoParaOtraEscuelaEInexistenteYUn400SinEcoParaUnIdNoUuid() throws Exception {
		MockHttpServletResponse deB = ejecutar(get("/api/admin/deportistas/" + ajeno + "/vinculos"), null);
		MockHttpServletResponse inexistente = ejecutar(get("/api/admin/deportistas/" + UUID.randomUUID() + "/vinculos"), null);
		MockHttpServletResponse listado = ejecutar(get("/api/admin/deportistas/" + ajeno + "/familias"), null);

		assertThat(deB.getStatus()).isEqualTo(404);
		assertThat(inexistente.getStatus()).isEqualTo(404);
		assertThat(deB.getContentAsString()).isEqualTo(inexistente.getContentAsString())
				.isEqualTo(listado.getContentAsString()).doesNotContain(ajeno.toString());
		assertThat(deB.getHeaderNames()).containsExactlyInAnyOrderElementsOf(inexistente.getHeaderNames());

		MockHttpServletResponse noUuid = ejecutar(get("/api/admin/deportistas/no-es-uuid-xyz/vinculos"), null);
		assertThat(noUuid.getStatus()).isEqualTo(400);
		assertThat(noUuid.getContentAsString()).contains("SOLICITUD_INVALIDA").doesNotContain("no-es-uuid-xyz");
	}

	@Test
	void laLecturaSinVinculosYConElDeportistaInactivoDevuelveSoloLosCamposDelContrato() throws Exception {
		MockHttpServletResponse r = ejecutar(get("/api/admin/deportistas/" + inactivo + "/vinculos"), null);
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(r.getContentAsString())
				.isEqualTo("{\"deportistaId\":\"" + inactivo + "\",\"versionVinculos\":\"0\",\"vinculos\":[]}");
	}

	@Test
	void revisionesConSignoEspaciosDigitosNoAsciiOCerosDeMasSeTratanSinEscribirYSinEco() throws Exception {
		ejecutar(post("/api/admin/familias/" + f1 + "/deportistas"),
				"{\"deportistas\":[{\"deportistaId\":\"" + d + "\",\"versionEsperada\":\"0\"}]}");
		String antes = hash();
		for (String valor : new String[] { "\"+1\"", "\"1 \"", "\"\\t1\"", "\"\u0661\"", "\"0x1\"", "1.0", "[\"1\"]",
				"{\"v\":\"1\"}" }) {
			for (String ruta : new String[] { "/revocar", "/principal" }) {
				MockHttpServletResponse r = ejecutar(post("/api/admin/familias/" + f1 + "/deportistas/" + d + ruta),
						"{\"versionEsperada\":" + valor + "}");
				assertThat(r.getStatus()).as(valor + ruta).isEqualTo(400);
				assertThat(r.getContentAsString()).contains("SOLICITUD_INVALIDA").doesNotContain(d.toString())
						.doesNotContain(f1.toString());
			}
			MockHttpServletResponse lote = ejecutar(post("/api/admin/familias/" + f1 + "/deportistas"),
					"{\"deportistas\":[{\"deportistaId\":\"" + d + "\",\"versionEsperada\":" + valor + "}]}");
			assertThat(lote.getStatus()).as(valor + " lote").isEqualTo(400);
			assertThat(lote.getContentAsString()).contains("SOLICITUD_INVALIDA").doesNotContain(d.toString());
		}
		// Ceros a la izquierda: misma regla que las demas revisiones (decimal ASCII), "01" es 1 y es la vigente.
		MockHttpServletResponse ceros = ejecutar(post("/api/admin/familias/" + f1 + "/deportistas/" + d + "/principal"),
				"{\"versionEsperada\":\"01\"}");
		assertThat(ceros.getStatus()).isEqualTo(200);
		assertThat(hash()).isEqualTo(antes);
	}

	@Test
	void obsoletasYAmbiguasNoDejanIdsNiRevisionesEnElCuerpoNiEnLosLogs() throws Exception {
		ejecutar(post("/api/admin/familias/" + f1 + "/deportistas"),
				"{\"deportistas\":[{\"deportistaId\":\"" + d + "\",\"versionEsperada\":\"0\"}]}");
		logs.list.clear();
		String antes = hash();
		MockHttpServletResponse obsoleta = ejecutar(post("/api/admin/familias/" + f1 + "/deportistas/" + d + "/revocar"),
				"{\"versionEsperada\":\"987654\"}");
		MockHttpServletResponse loteObsoleto = ejecutar(post("/api/admin/familias/" + f1 + "/deportistas"),
				"{\"deportistas\":[{\"deportistaId\":\"" + d + "\",\"versionEsperada\":\"987654\"}]}");
		MockHttpServletResponse ambigua = ejecutar(post("/api/admin/familias/" + f1 + "/deportistas"),
				"{\"deportistas\":[{\"deportistaId\":\"" + d + "\",\"versionEsperada\":\"1\"},{\"deportistaId\":\"" + d
						+ "\",\"versionEsperada\":\"987654\"}]}");
		assertThat(obsoleta.getStatus()).isEqualTo(409);
		assertThat(obsoleta.getContentAsString()).isEqualTo("{\"codigo\":\"EDICION_DESACTUALIZADA\",\"mensaje\":"
				+ "\"El registro fue modificado. Es necesario volver a cargarlo antes de guardar.\",\"detalles\":[]}");
		assertThat(loteObsoleto.getStatus()).isEqualTo(409);
		assertThat(loteObsoleto.getContentAsString()).contains("deportistas[0]").doesNotContain("987654")
				.doesNotContain(d.toString());
		assertThat(ambigua.getStatus()).isEqualTo(400);
		assertThat(ambigua.getContentAsString()).contains("SOLICITUD_INVALIDA").doesNotContain("987654")
				.doesNotContain(d.toString());
		assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains(d.toString())
				|| e.getFormattedMessage().contains("987654") || e.getFormattedMessage().contains("Nombre")
				|| (e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null
						&& e.getThrowableProxy().getMessage().contains("987654")));
		assertThat(hash()).isEqualTo(antes);
	}
}
