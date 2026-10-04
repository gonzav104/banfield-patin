package com.banfieldpatin.backend.usuarios.mfa;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.seguridad.CookieSesion;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;
import com.banfieldpatin.backend.usuarios.mfa.dto.CodigoMfaSolicitud;
import com.banfieldpatin.backend.usuarios.mfa.dto.EnrolamientoMfaRespuesta;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Segundo factor de ADMIN. La cadena de seguridad solo deja pasar aqui un token con MFA pendiente (autoridad
 * MFA_PENDIENTE); confirmar y verificar canjean ese token por la sesion completa (cookie nueva).
 */
@RestController
@RequestMapping("/api/auth/admin/mfa")
public class MfaController {

	private final MfaService servicio;
	private final ServicioTokens tokens;
	private final CookieSesion cookieSesion;
	private final CookieCsrfTokenRepository csrfRepo;

	public MfaController(MfaService servicio, ServicioTokens tokens, CookieSesion cookieSesion,
			CookieCsrfTokenRepository csrfRepo) {
		this.servicio = servicio;
		this.tokens = tokens;
		this.cookieSesion = cookieSesion;
		this.csrfRepo = csrfRepo;
	}

	@PostMapping("/enrolar")
	public ResponseEntity<EnrolamientoMfaRespuesta> enrolar(@AuthenticationPrincipal Jwt jwt,
			HttpServletRequest request) {
		EnrolamientoMfaRespuesta respuesta = servicio.enrolar(UsuarioAutenticado.desde(jwt), DatosSolicitud.de(request));
		// El secreto y la URI se muestran una sola vez: que ninguna cache los retenga.
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(respuesta);
	}

	@PostMapping("/confirmar")
	public ResponseEntity<UsuarioActualRespuesta> confirmar(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody CodigoMfaSolicitud solicitud, HttpServletRequest request,
			HttpServletResponse response) {
		UsuarioActualRespuesta usuario = servicio.confirmar(UsuarioAutenticado.desde(jwt), solicitud.codigo(),
				DatosSolicitud.de(request));
		return sesionCompleta(usuario, request, response);
	}

	@PostMapping("/verificar")
	public ResponseEntity<UsuarioActualRespuesta> verificar(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody CodigoMfaSolicitud solicitud, HttpServletRequest request,
			HttpServletResponse response) {
		UsuarioActualRespuesta usuario = servicio.verificar(UsuarioAutenticado.desde(jwt), solicitud.codigo(),
				DatosSolicitud.de(request));
		return sesionCompleta(usuario, request, response);
	}

	private ResponseEntity<UsuarioActualRespuesta> sesionCompleta(UsuarioActualRespuesta usuario,
			HttpServletRequest request, HttpServletResponse response) {
		String token = tokens.emitir(usuario.id(), usuario.rol(), usuario.escuelaId(), null);
		// Cambia el nivel de privilegio: se descarta el token CSRF anterior (el SPA vuelve a pedir /api/auth/csrf).
		csrfRepo.saveToken(null, request, response);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.header(HttpHeaders.SET_COOKIE, cookieSesion.crear(token).toString())
				.body(usuario);
	}
}
