package com.banfieldpatin.backend.usuarios.mfa;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.servlet.http.HttpServletRequest;

/** Solo ADMIN con sesion completa (la cadena protege /api/admin/**). La escuela sale del JWT. */
@RestController
@RequestMapping("/api/admin/usuarios")
public class MfaAdminController {

	private final MfaService servicio;

	public MfaAdminController(MfaService servicio) {
		this.servicio = servicio;
	}

	@PostMapping("/{id}/mfa/reiniciar")
	public ResponseEntity<Void> reiniciar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			HttpServletRequest request) {
		servicio.reiniciar(UsuarioAutenticado.desde(jwt), id, DatosSolicitud.de(request));
		return ResponseEntity.noContent().build();
	}
}
