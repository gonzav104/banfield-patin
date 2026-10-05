package com.banfieldpatin.backend.seguridad;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import com.banfieldpatin.backend.compartido.error.ErrorRespuesta;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Falla de autenticacion del filtro de bearer (REQ-XC-09, design 9.2):
 * <ul>
 * <li>{@link SesionNoVigenteException}: 401 NO_AUTENTICADO con la cookie de sesion expirada (mismos atributos que
 * {@code /api/auth/me});</li>
 * <li>{@link AuthenticationServiceException} (la base no pudo responder): 503 SERVICIO_NO_DISPONIBLE y la cookie NO se
 * toca, porque el estado de la sesion es desconocido y un 401 cerraria sesiones durante una caida;</li>
 * <li>cualquier otra: el 401 de siempre del {@link PuntoEntradaJson}, sin tocar la cookie.</li>
 * </ul>
 * El error del 503 se registra sin token ni identificadores.
 */
public class ManejadorFalloToken implements AuthenticationFailureHandler {

	private static final Logger log = LoggerFactory.getLogger(ManejadorFalloToken.class);

	private final PuntoEntradaJson puntoEntrada;
	private final CookieSesion cookieSesion;
	private final JsonMapper jsonMapper;

	public ManejadorFalloToken(PuntoEntradaJson puntoEntrada, CookieSesion cookieSesion, JsonMapper jsonMapper) {
		this.puntoEntrada = puntoEntrada;
		this.cookieSesion = cookieSesion;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		if (exception instanceof SesionNoVigenteException) {
			response.addHeader(HttpHeaders.SET_COOKIE, cookieSesion.borrar().toString());
			puntoEntrada.commence(request, response, exception);
			return;
		}
		if (exception instanceof AuthenticationServiceException) {
			// Solo tipos de excepcion: ni mensajes (podrian traer SQL o ids) ni la traza completa por cada solicitud.
			log.error("No se pudo verificar la vigencia de la sesion: la base de datos no respondio ({}).",
					causaRaiz(exception).getClass().getName());
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding("UTF-8");
			jsonMapper.writeValue(response.getOutputStream(), ErrorRespuesta.de("SERVICIO_NO_DISPONIBLE",
					"El servicio no está disponible por el momento. Intentá de nuevo en unos instantes."));
			return;
		}
		puntoEntrada.commence(request, response, exception);
	}

	private static Throwable causaRaiz(Throwable t) {
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t;
	}
}
