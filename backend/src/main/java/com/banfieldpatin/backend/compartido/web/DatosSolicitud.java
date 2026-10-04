package com.banfieldpatin.backend.compartido.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP y user agent del cliente. La IP es getRemoteAddr(): detras de un proxy requiere
 * server.forward-headers-strategy; no se confia en X-Forwarded-For crudo.
 */
public record DatosSolicitud(String ip, String userAgent) {

	public static final DatosSolicitud NINGUNA = new DatosSolicitud(null, null);

	public static DatosSolicitud de(HttpServletRequest request) {
		return new DatosSolicitud(request.getRemoteAddr(), request.getHeader("User-Agent"));
	}
}
