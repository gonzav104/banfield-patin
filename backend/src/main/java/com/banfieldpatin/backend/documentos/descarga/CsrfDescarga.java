package com.banfieldpatin.backend.documentos.descarga;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.util.StringUtils;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;

import jakarta.servlet.http.HttpServletRequest;

/**
 * D2: la cookie de sesión es {@code SameSite=Lax}, que no impide una navegación GET de otro sitio; por
 * eso esta lectura exige además la cabecera CSRF, sólo en esta ruta y sin tocar la cadena global. Usa
 * el mismo repositorio que la cadena de seguridad y la misma semántica que su manejador SPA cuando la
 * cabecera está presente: el valor crudo de la cookie {@code XSRF-TOKEN}, comparado en tiempo constante.
 */
final class CsrfDescarga {
	private CsrfDescarga() {
	}

	static void exigir(HttpServletRequest request, CsrfTokenRepository repositorio) {
		CsrfToken esperado = repositorio.loadToken(request);
		String recibido = esperado == null ? null : request.getHeader(esperado.getHeaderName());
		if (esperado == null || !StringUtils.hasText(recibido) || !StringUtils.hasText(esperado.getToken())
				|| !MessageDigest.isEqual(esperado.getToken().getBytes(StandardCharsets.UTF_8),
						recibido.getBytes(StandardCharsets.UTF_8))) {
			throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "CSRF_INVALIDO", "Token CSRF ausente o inválido.");
		}
	}
}
