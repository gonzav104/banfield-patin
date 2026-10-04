package com.banfieldpatin.backend.seguridad;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.banfieldpatin.backend.compartido.error.ErrorRespuesta;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** 401 JSON generico: no revela el motivo (token vencido, firma invalida, etc.). */
@Component
public class PuntoEntradaJson implements AuthenticationEntryPoint {

	private final JsonMapper jsonMapper;

	public PuntoEntradaJson(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		jsonMapper.writeValue(response.getOutputStream(),
				ErrorRespuesta.de("NO_AUTENTICADO", "Necesitás iniciar sesión para acceder."));
	}
}
