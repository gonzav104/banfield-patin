package com.banfieldpatin.backend.familias.tutores;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.tutores.dto.TutorRespuesta;
import com.banfieldpatin.backend.familias.tutores.dto.TutorSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Tutores para ADMIN (la cadena de seguridad protege /api/admin/**). La escuela y el actor salen del JWT. No hay DELETE
 * ni PATCH: un metodo no mapeado responde 405 METODO_NO_PERMITIDO. La familia de un tutor no se puede cambiar.
 */
@RestController
@RequestMapping("/api/admin")
public class TutorAdminController {

	private final TutorAdminService servicio;

	public TutorAdminController(TutorAdminService servicio) {
		this.servicio = servicio;
	}

	@PostMapping("/familias/{familiaId}/tutores")
	public ResponseEntity<TutorRespuesta> crear(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familiaId,
			@Valid @RequestBody TutorSolicitud solicitud, HttpServletRequest request) {
		TutorRespuesta creado = servicio.crear(UsuarioAutenticado.desde(jwt), familiaId, solicitud,
				DatosSolicitud.de(request));
		return ResponseEntity.created(URI.create("/api/admin/tutores/" + creado.id())).body(creado);
	}

	@GetMapping("/tutores/{id}")
	public TutorRespuesta obtener(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return servicio.obtener(UsuarioAutenticado.desde(jwt), id);
	}

	@PutMapping("/tutores/{id}")
	public TutorRespuesta actualizar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody TutorSolicitud solicitud, HttpServletRequest request) {
		return servicio.actualizar(UsuarioAutenticado.desde(jwt), id, solicitud, DatosSolicitud.de(request));
	}
}
