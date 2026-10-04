package com.banfieldpatin.backend.familias.invitaciones;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.invitaciones.dto.CrearInvitacionSolicitud;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionCreadaRespuesta;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionRespuesta;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** Solo ADMIN (la cadena de seguridad protege /api/admin/**). La escuela y el actor salen del JWT. */
@RestController
@RequestMapping("/api/admin/invitaciones")
public class InvitacionAdminController {

	private final InvitacionAdminService servicio;

	public InvitacionAdminController(InvitacionAdminService servicio) {
		this.servicio = servicio;
	}

	@PostMapping
	public ResponseEntity<InvitacionCreadaRespuesta> crear(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody CrearInvitacionSolicitud solicitud, HttpServletRequest request) {
		InvitacionCreadaRespuesta creada = servicio.crear(UsuarioAutenticado.desde(jwt), solicitud,
				DatosSolicitud.de(request));
		// La respuesta lleva el token en claro, visible una sola vez: que ninguna cache la retenga.
		return ResponseEntity.created(URI.create("/api/admin/invitaciones/" + creada.id()))
				.cacheControl(CacheControl.noStore())
				.body(creada);
	}

	@GetMapping
	public Pagina<InvitacionRespuesta> listar(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) EstadoInvitacion estado,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + Pagina.TAMANIO_POR_DEFECTO) int size) {
		return servicio.listar(UsuarioAutenticado.desde(jwt), estado, Pagina.pedir(page, size));
	}

	@GetMapping("/{id}")
	public InvitacionRespuesta obtener(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return servicio.obtener(UsuarioAutenticado.desde(jwt), id);
	}

	@PostMapping("/{id}/revocar")
	public InvitacionRespuesta revocar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			HttpServletRequest request) {
		return servicio.revocar(UsuarioAutenticado.desde(jwt), id, DatosSolicitud.de(request));
	}
}
