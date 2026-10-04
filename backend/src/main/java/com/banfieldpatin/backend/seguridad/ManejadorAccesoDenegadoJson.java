package com.banfieldpatin.backend.seguridad;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import com.banfieldpatin.backend.compartido.error.ErrorRespuesta;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** 403 JSON: distingue fallo CSRF de falta de permisos. */
@Component
public class ManejadorAccesoDenegadoJson implements AccessDeniedHandler {

	private final JsonMapper jsonMapper;

	public ManejadorAccesoDenegadoJson(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		ErrorRespuesta cuerpo = accessDeniedException instanceof CsrfException
				? ErrorRespuesta.de("CSRF_INVALIDO", "Token CSRF ausente o inválido.")
				: ErrorRespuesta.de("ACCESO_DENEGADO", "No tenés permiso para realizar esta acción.");
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		jsonMapper.writeValue(response.getOutputStream(), cuerpo);
	}
}
