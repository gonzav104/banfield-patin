package com.banfieldpatin.backend.documentos.descarga;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Descarga auditada (U16): ADMIN con MFA completo por {@code /api/admin/**} y CSRF por cabecera (D2). */
@RestController
@RequestMapping("/api/admin")
public class DescargaDocumentoController {

	private final DescargaDocumentoService servicio;
	private final CsrfTokenRepository csrf;

	public DescargaDocumentoController(DescargaDocumentoService servicio, CsrfTokenRepository csrf) {
		this.servicio = servicio;
		this.csrf = csrf;
	}

	@GetMapping("/deportistas/{deportistaId}/documentos/{documentoId}/archivo")
	public void archivo(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId,
			@PathVariable UUID documentoId, HttpServletRequest request, HttpServletResponse response)
			throws HttpRequestMethodNotSupportedException {
		// Spring atiende HEAD con este método: un HEAD leería Storage y auditaría una entrega que no ocurre.
		if ("HEAD".equalsIgnoreCase(request.getMethod())) throw new HttpRequestMethodNotSupportedException("HEAD", Set.of("GET"));
		CsrfDescarga.exigir(request, csrf);
		servicio.descargar(UsuarioAutenticado.desde(jwt), deportistaId, documentoId, DatosSolicitud.de(request),
				(mime, nombre, contenido) -> escribir(response, mime, nombre, contenido));
	}

	/** Sólo se llama con bytes ya verificados y auditados; nada se guarda en disco ni en caché. */
	private static void escribir(HttpServletResponse response, String mime, String nombre, byte[] contenido)
			throws IOException {
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType(mime);
		response.setContentLengthLong(contenido.length);
		response.setHeader("Content-Disposition", ContentDisposition.attachment().filename(nombre).build().toString());
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
		var salida = response.getOutputStream();
		salida.write(contenido);
		salida.flush();
	}
}
