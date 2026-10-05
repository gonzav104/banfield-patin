package com.banfieldpatin.backend.compartido.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@WebMvcTest(controllers = ControladorSondaErrores.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ManejadorGlobalErrores.class)
@ActiveProfiles("test")
class ManejadorGlobalErroresTest {

	@Autowired
	MockMvc mvc;

	private Logger raiz;
	private Level nivelOriginal;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void capturarLogs() {
		raiz = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		nivelOriginal = raiz.getLevel();
		raiz.setLevel(Level.INFO);
		logs = new ListAppender<>();
		logs.start();
		raiz.addAppender(logs);
	}

	@AfterEach
	void liberarLogs() {
		raiz.detachAppender(logs);
		raiz.setLevel(nivelOriginal);
	}

	@Test
	void validacionListaDetallesSinEcoDeValores() throws Exception {
		mvc.perform(post("/sonda/validar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"nombre\":\"\",\"password\":\"corta\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"))
				.andExpect(jsonPath("$.detalles.length()").value(2))
				.andExpect(jsonPath("$.detalles[?(@.campo=='nombre')]").exists())
				.andExpect(jsonPath("$.detalles[?(@.campo=='password')]").exists())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("corta"))));
	}

	@Test
	void excepcionInesperadaDevuelve500GenericoSinFugas() throws Exception {
		mvc.perform(get("/sonda/explota"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.codigo").value("ERROR_INTERNO"))
				.andExpect(jsonPath("$.detalles").isArray())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secreto"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("IllegalState"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("jdbc"))));
	}

	@Test
	void jsonMalformadoDevuelve400Uniforme() throws Exception {
		mvc.perform(post("/sonda/validar").contentType(MediaType.APPLICATION_JSON).content("{no es json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
				.andExpect(jsonPath("$.detalles").isArray());
	}

	@Test
	void metodoNoPermitidoYTipoNoSoportadoMantienenFormato() throws Exception {
		mvc.perform(get("/sonda/validar"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
		mvc.perform(post("/sonda/validar").contentType(MediaType.TEXT_PLAIN).content("x"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.codigo").value("TIPO_MEDIO_NO_SOPORTADO"));
	}

	@Test
	void excepcionNegocioUsaSuEstadoYCodigo() throws Exception {
		mvc.perform(get("/sonda/negocio"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("CODIGO_X"));
	}

	@Test
	void rutaInexistenteDevuelve404Uniforme() throws Exception {
		mvc.perform(get("/no/existe"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("RECURSO_NO_ENCONTRADO"));
	}

	// ---------- lock_timeout vencido / deadlock -> 409 CONFLICTO_CONCURRENCIA ----------

	private static final String CUERPO_CONCURRENCIA = "{\"codigo\":\"CONFLICTO_CONCURRENCIA\",\"mensaje\":"
			+ "\"Otro cambio sobre el mismo deportista esta en curso. Reintentá en unos segundos.\",\"detalles\":[]}";

	@Test
	void unBloqueoVencidoDevuelve409ConCodigoYMensajeFijosSinSqlNiIdsNiClases() throws Exception {
		String cuerpo = mvc.perform(post("/sonda/bloqueo/7b3f2c1e-9999-8888-7777-666655554444"))
				.andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

		assertThat(cuerpo).isEqualTo(CUERPO_CONCURRENCIA);
		assertThat(cuerpo).doesNotContain("relation").doesNotContain("select").doesNotContain("gestion_patin")
				.doesNotContain("7b3f2c1e").doesNotContain("55P03").doesNotContain("Exception").doesNotContain("lock timeout")
				.doesNotContain("for update");
	}

	@Test
	void unDeadlockDeLaBaseSeTraduceAlMismoConflictoYNuncaA500() throws Exception {
		String cuerpo = mvc.perform(get("/sonda/deadlock")).andExpect(status().isConflict()).andReturn().getResponse()
				.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

		assertThat(cuerpo).isEqualTo(CUERPO_CONCURRENCIA);
	}

	@Test
	void elBloqueoVencidoDejaUnSoloWarnConOperacionYClaseSinMensajeNiTrazaNiIds() throws Exception {
		mvc.perform(post("/sonda/bloqueo/7b3f2c1e-9999-8888-7777-666655554444")).andExpect(status().isConflict());

		assertThat(logs.list).singleElement().satisfies(e -> {
			assertThat(e.getLevel()).isEqualTo(Level.WARN);
			assertThat(e.getLoggerName()).isEqualTo(ManejadorGlobalErrores.class.getName());
			// La operacion es el PATRON de la ruta, no la URL real (que podria llevar un id).
			assertThat(e.getFormattedMessage()).isEqualTo("Conflicto de concurrencia por bloqueo de base de datos (409): "
					+ "operacion=POST /sonda/bloqueo/{id} excepcion=org.springframework.dao.CannotAcquireLockException");
			assertThat(e.getThrowableProxy()).isNull();
			assertThat(e.getArgumentArray()).doesNotContain(ControladorSondaErrores.MENSAJE_BLOQUEO);
		});
		for (ILoggingEvent evento : logs.list) {
			assertThat(evento.getFormattedMessage()).doesNotContain("deportista").doesNotContain("select")
					.doesNotContain("7b3f2c1e").doesNotContain("55P03").doesNotContain("for update");
		}
	}

	@Test
	void unBloqueoVencidoNoLlegaAlManejadorGeneralDe500NiRegistraUnaTraza() throws Exception {
		mvc.perform(get("/sonda/deadlock")).andExpect(status().isConflict());

		assertThat(logs.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.ERROR));
		assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("Error interno no controlado"));
		assertThat(logs.list).allMatch(e -> e.getThrowableProxy() == null);
	}
}
