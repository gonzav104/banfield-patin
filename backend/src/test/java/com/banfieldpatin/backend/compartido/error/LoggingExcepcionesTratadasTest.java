package com.banfieldpatin.backend.compartido.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Comportamiento REAL de los logs con el nivel RAIZ forzado a DEBUG (el peor caso: alguien activa DEBUG en produccion):
 * una solicitud que dispara una violacion de restriccion tratada, cuyo mensaje crudo lleva un DNI y la linea
 * {@code Detail: Key (...)} de PostgreSQL, NO deja ningun evento de log con el DNI, "Detail:" ni "Key (" (solo la linea
 * saneada de la aplicacion). Los niveles efectivos son los que aplica el LoggingSystem de Spring Boot al contexto de la
 * prueba a partir del application.yml real (se comprueba en cada prueba que {@code logging.level.*} se aplico), no los que
 * fije la prueba. Un CONTROL negativo quita el nivel fijado del logger y demuestra que, sin el, el mismo mensaje SI se
 * filtraba (asi la prueba no pasa en vacio).
 */
@WebMvcTest(controllers = ControladorSondaErrores.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ManejadorGlobalErrores.class)
@ActiveProfiles("test")
class LoggingExcepcionesTratadasTest {

	private static final String DNI = ControladorSondaErrores.DNI_SECRETO;
	private static final String MVC = "org.springframework.web.servlet.mvc.";
	private static final String RESOLUTOR_DE_HANDLERS = MVC + "method.annotation.ExceptionHandlerExceptionResolver";
	private static final String RESOLUTOR_POR_DEFECTO = MVC + "support.DefaultHandlerExceptionResolver";
	private static final String RESOLUTOR_DE_ESTADO = MVC + "annotation.ResponseStatusExceptionResolver";

	@Autowired
	MockMvc mvc;
	@Autowired
	LoggingSystem sistemaDeLogs;

	private Logger raiz;
	private Level nivelRaizOriginal;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void raizEnDebug() {
		raiz = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		nivelRaizOriginal = raiz.getLevel();
		raiz.setLevel(Level.DEBUG);
		logs = new ListAppender<>();
		logs.start();
		raiz.addAppender(logs);
	}

	@AfterEach
	void restaurar() {
		raiz.detachAppender(logs);
		raiz.setLevel(nivelRaizOriginal);
	}

	private void nivelConfigurado(String logger, LogLevel esperado) {
		var configuracion = sistemaDeLogs.getLoggerConfiguration(logger);
		assertThat(configuracion).as("el LoggingSystem no conoce el logger %s", logger).isNotNull();
		assertThat(configuracion.getConfiguredLevel())
				.as("nivel aplicado por Spring Boot a %s desde application.yml", logger).isEqualTo(esperado);
	}

	private static void sinFiltracion(List<ILoggingEvent> eventos) {
		for (ILoggingEvent e : eventos) {
			String texto = e.getFormattedMessage() + " " + e.getThrowableProxy();
			assertThat(texto).as("evento de %s (%s)", e.getLoggerName(), e.getLevel()).doesNotContain(DNI)
					.doesNotContain("Detail:").doesNotContain("Key (").doesNotContain("duplicate key")
					.doesNotContain("11111111-2222-3333-4444-555555555555");
		}
	}

	private static boolean filtra(ILoggingEvent e) {
		return e.getFormattedMessage().contains(DNI) || e.getFormattedMessage().contains("Key (");
	}

	// ---------- los niveles del application.yml REAL llegaron al contexto de la prueba ----------

	@Test
	void springBootAplicoAlContextoDePruebaLosNivelesDelApplicationYml() {
		nivelConfigurado(RESOLUTOR_DE_HANDLERS, LogLevel.INFO);
		nivelConfigurado(RESOLUTOR_DE_ESTADO, LogLevel.INFO);
		nivelConfigurado("org.hibernate.orm.jdbc.error", LogLevel.OFF);
		assertThat(raiz.getLevel()).as("la raiz esta forzada a DEBUG por esta prueba").isEqualTo(Level.DEBUG);
	}

	// ---------- solicitud real con la raiz en DEBUG ----------

	@Test
	void unaViolacionDeRestriccionTratadaConLaRaizEnDebugNoDejaDniNiDetailNiKeyEnNingunLog() throws Exception {
		MockHttpServletResponse r = mvc.perform(post("/sonda/violacion")).andReturn().getResponse();

		assertThat(r.getStatus()).isEqualTo(500);
		assertThat(r.getContentAsString()).contains("ERROR_INTERNO").doesNotContain(DNI).doesNotContain("Detail");
		// Solo queda la linea saneada de la aplicacion (restriccion + operacion + clase), con el mensaje crudo ausente.
		assertThat(logs.list).anySatisfy(e -> {
			assertThat(e.getLoggerName()).endsWith("RestriccionViolada");
			assertThat(e.getFormattedMessage()).isEqualTo("Violacion de restriccion sin mapear (500): restriccion=uq_otra "
					+ "operacion=POST /sonda/violacion excepcion=org.springframework.dao.DataIntegrityViolationException");
		});
		sinFiltracion(logs.list);
		assertThat(logs.list).noneMatch(LoggingExcepcionesTratadasTest::filtra);
	}

	@Test
	void controlNegativoSinElNivelFijadoElMismoMensajeSiLlegaAlLogDeDebug() throws Exception {
		Logger resolutor = (Logger) LoggerFactory.getLogger(RESOLUTOR_DE_HANDLERS);
		Level fijado = resolutor.getLevel();
		assertThat(fijado).isEqualTo(Level.INFO);
		resolutor.setLevel(null); // hereda la raiz (DEBUG): lo que pasaria sin la configuracion explicita
		try {
			mvc.perform(post("/sonda/violacion")).andReturn();

			assertThat(logs.list).as("sin el pin, 'Resolved [...]' expone el mensaje del servidor")
					.anyMatch(e -> e.getLoggerName().equals(RESOLUTOR_DE_HANDLERS) && filtra(e));
		} finally {
			resolutor.setLevel(fijado);
		}
	}

	// ---------- los otros resolutores del mismo camino de codigo ----------

	/** Resuelve la excepcion con el resolutor REAL y devuelve los eventos de ese logger. */
	private List<ILoggingEvent> resolver(String logger, java.util.function.Consumer<MockHttpServletRequest> trabajo) {
		trabajo.accept(new MockHttpServletRequest());
		return logs.list.stream().filter(e -> e.getLoggerName().equals(logger)).toList();
	}

	private static String mensajeConDni() {
		return "valor rechazado " + DNI + " Detail: Key (dni)=(" + DNI + ")";
	}

	/**
	 * DefaultHandlerExceptionResolver registra su linea "Resolved [...]" a nivel WARN (setWarnLogCategory), por lo que un
	 * pin en INFO no la silenciaria y por eso no se fija. Lo que SI se comprueba es que es inalcanzable en esta aplicacion:
	 * ManejadorGlobalErrores (ExceptionHandlerExceptionResolver va primero y tiene un @ExceptionHandler(Exception)) resuelve
	 * antes cualquier excepcion de un controlador, incluidas las que ese resolutor trataria (aqui un JSON ilegible).
	 */
	@Test
	void defaultHandlerExceptionResolverEsInalcanzableDetrasDelManejadorGlobalYNoLogueaNada() throws Exception {
		MockHttpServletResponse r = mvc.perform(post("/sonda/validar").contentType("application/json")
				.content("{\"nombre\": \"" + DNI + "\", \"password\": ")).andReturn().getResponse();

		assertThat(r.getStatus()).isEqualTo(400);
		assertThat(r.getContentAsString()).contains("SOLICITUD_INVALIDA");
		assertThat(logs.list).noneMatch(e -> e.getLoggerName().equals(RESOLUTOR_POR_DEFECTO));
		sinFiltracion(logs.list);
	}

	@Test
	void hechoVerificadoDefaultHandlerExceptionResolverEmiteSuLineaResolvedEnWarnYConElMensaje() {
		var resolutor = new DefaultHandlerExceptionResolver();
		resolutor.resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(), null,
				new HttpMessageNotReadableException(mensajeConDni(), new MockHttpInputMessageVacio()));

		assertThat(logs.list).filteredOn(e -> e.getLoggerName().equals(RESOLUTOR_POR_DEFECTO)).singleElement()
				.satisfies(e -> {
					assertThat(e.getLevel()).as("WARN: ningun pin en INFO lo silencia").isEqualTo(Level.WARN);
					assertThat(e.getFormattedMessage()).startsWith("Resolved [").contains(DNI);
				});
	}

	@Test
	void responseStatusExceptionResolverConLaRaizEnDebugNoImprimeElMensajeDeLaExcepcion() {
		var resolutor = new ResponseStatusExceptionResolver();
		var ex = new ResponseStatusException(HttpStatus.CONFLICT, mensajeConDni());
		var eventos = resolver(RESOLUTOR_DE_ESTADO,
				req -> assertThat(resolutor.resolveException(req, new MockHttpServletResponse(), null, ex)).isNotNull());

		assertThat(eventos).noneMatch(LoggingExcepcionesTratadasTest::filtra);
		sinFiltracion(logs.list);
	}

	@Test
	void controlNegativoResponseStatusExceptionResolverSiImprimeElMensajeSinElNivelFijado() {
		Logger l = (Logger) LoggerFactory.getLogger(RESOLUTOR_DE_ESTADO);
		Level fijado = l.getLevel();
		l.setLevel(null);
		try {
			new ResponseStatusExceptionResolver().resolveException(new MockHttpServletRequest(),
					new MockHttpServletResponse(), null, new ResponseStatusException(HttpStatus.CONFLICT, mensajeConDni()));

			assertThat(logs.list).anyMatch(e -> e.getLoggerName().equals(RESOLUTOR_DE_ESTADO) && e.getFormattedMessage().contains(DNI));
		} finally {
			l.setLevel(fijado);
		}
	}

	@Test
	void elResolutorDeHandlersYaNoEsElUnicoPeroSiElQueTrataLaAplicacion() {
		// El ExceptionHandlerExceptionResolver real del contexto usa el logger fijado en INFO.
		assertThat(LoggerFactory.getLogger(ExceptionHandlerExceptionResolver.class).getName())
				.isEqualTo(RESOLUTOR_DE_HANDLERS);
		assertThat(((Logger) LoggerFactory.getLogger(RESOLUTOR_DE_HANDLERS)).getLevel()).isEqualTo(Level.INFO);
	}

	/** Mensaje HTTP vacio: HttpMessageNotReadableException exige uno en su constructor con causa. */
	private static final class MockHttpInputMessageVacio implements org.springframework.http.HttpInputMessage {
		@Override
		public java.io.InputStream getBody() {
			return java.io.InputStream.nullInputStream();
		}

		@Override
		public org.springframework.http.HttpHeaders getHeaders() {
			return new org.springframework.http.HttpHeaders();
		}
	}
}
