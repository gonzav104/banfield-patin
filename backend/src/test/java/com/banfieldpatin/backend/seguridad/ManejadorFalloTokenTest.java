package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.json.JsonMapper;

/**
 * REQ-XC-09 S1-S4, S10 / design 9.2: el manejador limpia la cookie SOLO ante una sesion no vigente (401); ante una
 * falla de la base responde 503 sin tocarla; cualquier otro rechazo sigue el 401 de siempre.
 */
class ManejadorFalloTokenTest {

	private static final SeguridadPropiedades PROPIEDADES = new SeguridadPropiedades(
			new SeguridadPropiedades.Jwt("dGVzdC1vbmx5LWZpY3RpdGlvdXMtand0LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVm", "e",
					Duration.ofHours(8)),
			new SeguridadPropiedades.Cookie("BP_SESION", false, "Lax"),
			new SeguridadPropiedades.Cors(null),
			new SeguridadPropiedades.Login(5, Duration.ofMinutes(15), Duration.ofMinutes(15), 100),
			new SeguridadPropiedades.Mfa("dGVzdC1vbmx5LWZpY3RpdGlvdXMtbWZhLWtleS0zMmI=", Duration.ofMinutes(5),
					"Banfield Patin"));

	private final JsonMapper jsonMapper = JsonMapper.builder().build();
	private final CookieSesion cookieSesion = new CookieSesion(PROPIEDADES);
	private final ManejadorFalloToken manejador = new ManejadorFalloToken(new PuntoEntradaJson(jsonMapper), cookieSesion,
			jsonMapper);

	private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/ping");
	private final MockHttpServletResponse response = new MockHttpServletResponse();

	private ListAppender<ILoggingEvent> logs;
	private Logger logger;

	@BeforeEach
	void capturarLogs() {
		logger = (Logger) LoggerFactory.getLogger(ManejadorFalloToken.class);
		logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
	}

	@AfterEach
	void liberarLogs() {
		logger.detachAppender(logs);
	}

	private String codigo() throws Exception {
		return jsonMapper.readTree(response.getContentAsString()).get("codigo").asString();
	}

	@Test
	void sesionNoVigenteDa401ConElCuerpoEstandarYLimpiaLaCookieConLosMismosAtributosQueMe() throws Exception {
		manejador.onAuthenticationFailure(request, response, new SesionNoVigenteException("inactivo"));

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentType()).startsWith("application/json");
		assertThat(codigo()).isEqualTo("NO_AUTENTICADO");
		// El mock normaliza el formato de Expires, asi que se comparan los atributos y no el texto completo.
		assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(1);
		assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("BP_SESION=;").contains("Max-Age=0")
				.contains("Expires=Thu, 1 Jan 1970").contains("HttpOnly").contains("Path=/").contains("SameSite=Lax");
		assertThat(cookieSesion.borrar().toString()).contains("BP_SESION=;").contains("Max-Age=0").contains("HttpOnly")
				.contains("Path=/").contains("SameSite=Lax");
	}

	@Test
	void falloDeLaBaseDa503ServicioNoDisponibleSinTocarLaCookieYRegistraSinDatos() throws Exception {
		UUID usuarioId = UUID.randomUUID();
		var ex = new AuthenticationServiceException("No se pudo verificar la vigencia de la sesion.",
				new DataAccessResourceFailureException("base caida"));

		manejador.onAuthenticationFailure(request, response, ex);

		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentType()).startsWith("application/json");
		assertThat(codigo()).isEqualTo("SERVICIO_NO_DISPONIBLE");
		assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
		assertThat(response.getContentAsString()).doesNotContain("base caida").doesNotContain("Exception")
				.doesNotContain(usuarioId.toString());

		List<ILoggingEvent> errores = logs.list.stream().filter(e -> e.getLevel().toString().equals("ERROR")).toList();
		assertThat(errores).hasSize(1);
		assertThat(errores.get(0).getFormattedMessage()).doesNotContain("token-opaco").doesNotContain(usuarioId.toString());
	}

	@Test
	void cualquierOtroRechazoDelegaEnElPuntoDeEntradaYNoLimpiaLaCookie() throws Exception {
		for (var ex : List.of(new InvalidBearerTokenException("vencido"), new BadCredentialsException("x"))) {
			MockHttpServletResponse r = new MockHttpServletResponse();

			manejador.onAuthenticationFailure(request, r, ex);

			assertThat(r.getStatus()).isEqualTo(401);
			assertThat(r.getContentAsString()).contains("NO_AUTENTICADO");
			assertThat(r.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
		}
	}

	@Test
	void el401DeSesionNoVigenteTieneElMismoCuerpoQueElPuntoDeEntrada() throws Exception {
		MockHttpServletResponse referencia = new MockHttpServletResponse();
		new PuntoEntradaJson(jsonMapper).commence(request, referencia, new BadCredentialsException("x"));

		manejador.onAuthenticationFailure(request, response, new SesionNoVigenteException("inactivo"));

		assertThat(response.getContentAsString()).isEqualTo(referencia.getContentAsString());
	}
}
