package com.banfieldpatin.backend.usuarios;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.error.ErrorRespuesta;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.seguridad.CookieSesion;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.dto.LoginSolicitud;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AutenticacionController {

	private final AutenticacionService servicio;
	private final ServicioTokens tokens;
	private final CookieSesion cookieSesion;
	private final CookieCsrfTokenRepository csrfRepo;

	public AutenticacionController(AutenticacionService servicio, ServicioTokens tokens, CookieSesion cookieSesion,
			CookieCsrfTokenRepository csrfRepo) {
		this.servicio = servicio;
		this.tokens = tokens;
		this.cookieSesion = cookieSesion;
		this.csrfRepo = csrfRepo;
	}

	@PostMapping("/login")
	public ResponseEntity<UsuarioActualRespuesta> login(@Valid @RequestBody LoginSolicitud solicitud,
			HttpServletRequest request, HttpServletResponse response) {
		return iniciarSesion(Rol.FAMILIA, solicitud, request, response);
	}

	@PostMapping("/admin/login")
	public ResponseEntity<UsuarioActualRespuesta> loginAdmin(@Valid @RequestBody LoginSolicitud solicitud,
			HttpServletRequest request, HttpServletResponse response) {
		return iniciarSesion(Rol.ADMIN, solicitud, request, response);
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
			HttpServletResponse response) {
		servicio.cerrarSesion(UsuarioAutenticado.desde(jwt), DatosSolicitud.de(request));
		csrfRepo.saveToken(null, request, response);
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookieSesion.borrar().toString()).build();
	}

	@GetMapping("/me")
	public ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt) {
		return servicio.actual(UsuarioAutenticado.desde(jwt))
				.map(u -> u.conMfaPendiente(ServicioTokens.mfaPendiente(jwt)))
				.<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.status(401)
						.header(HttpHeaders.SET_COOKIE, cookieSesion.borrar().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.body(ErrorRespuesta.de("NO_AUTENTICADO", "Necesitás iniciar sesión para acceder.")));
	}

	private ResponseEntity<UsuarioActualRespuesta> iniciarSesion(Rol rol, LoginSolicitud solicitud,
			HttpServletRequest request, HttpServletResponse response) {
		UsuarioActualRespuesta usuario = servicio.autenticar(rol, solicitud.email(), solicitud.password(),
				DatosSolicitud.de(request));
		// Se descarta la cookie XSRF previa a la sesion; el SPA vuelve a pedir /api/auth/csrf.
		csrfRepo.saveToken(null, request, response);
		if (usuario.rol() == Rol.ADMIN) {
			// RNF-03: la contrasena sola no abre una sesion de ADMIN; queda con MFA pendiente y token de vida corta.
			String pendiente = tokens.emitirMfaPendiente(usuario.id(), usuario.escuelaId());
			return ResponseEntity.ok()
					.header(HttpHeaders.SET_COOKIE, cookieSesion.crearMfaPendiente(pendiente).toString())
					.body(usuario.conMfaPendiente(true));
		}
		String token = tokens.emitir(usuario.id(), usuario.rol(), usuario.escuelaId(), usuario.familiaId());
		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookieSesion.crear(token).toString()).body(usuario);
	}
}
