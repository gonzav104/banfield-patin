package com.banfieldpatin.backend.familias.perfil;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.perfil.dto.PerfilActualizacion;
import com.banfieldpatin.backend.familias.perfil.dto.PerfilRespuesta;
import com.banfieldpatin.backend.familias.perfil.dto.PerfilSolicitud;
import com.banfieldpatin.backend.familias.perfil.dto.ReclamoSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Perfil propio de la cuenta FAMILIA (U21-BIS C1). La cadena de seguridad exige el rol FAMILIA en {@code /api/familia/**};
 * escuela, familia y cuenta salen del token. Son datos personales: nunca se cachean.
 */
@RestController
@RequestMapping("/api/familia/mi-perfil")
public class PerfilPropioController {

	private final PerfilPropioService servicio;

	public PerfilPropioController(PerfilPropioService servicio) {
		this.servicio = servicio;
	}

	@GetMapping
	public ResponseEntity<PerfilRespuesta> obtener(@AuthenticationPrincipal Jwt jwt) {
		return privado(servicio.obtener(UsuarioAutenticado.desde(jwt)));
	}

	@PostMapping
	public ResponseEntity<PerfilRespuesta> crear(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PerfilSolicitud solicitud,
			HttpServletRequest request) {
		return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
				.body(servicio.crear(UsuarioAutenticado.desde(jwt), solicitud, DatosSolicitud.de(request)));
	}

	@PutMapping
	public ResponseEntity<PerfilRespuesta> actualizar(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody PerfilActualizacion solicitud, HttpServletRequest request) {
		return privado(servicio.actualizar(UsuarioAutenticado.desde(jwt), solicitud, DatosSolicitud.de(request)));
	}

	@PostMapping("/reclamar-tutor")
	public ResponseEntity<PerfilRespuesta> reclamar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReclamoSolicitud solicitud,
			HttpServletRequest request) {
		return privado(servicio.reclamar(UsuarioAutenticado.desde(jwt), solicitud, DatosSolicitud.de(request)));
	}

	private static ResponseEntity<PerfilRespuesta> privado(PerfilRespuesta cuerpo) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cuerpo);
	}
}
