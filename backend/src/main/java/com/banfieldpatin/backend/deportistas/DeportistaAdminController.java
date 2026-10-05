package com.banfieldpatin.backend.deportistas;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.FiltroEstado;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.deportistas.dto.DeportistaDetalle;
import com.banfieldpatin.backend.deportistas.dto.DeportistaResumen;
import com.banfieldpatin.backend.deportistas.dto.DeportistaSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Gestion de deportistas para ADMIN (la cadena de seguridad protege /api/admin/**). La escuela y el actor salen del JWT.
 * No hay DELETE ni PATCH (405 METODO_NO_PERMITIDO): un deportista se desactiva, nunca se borra.
 */
@RestController
@RequestMapping("/api/admin/deportistas")
public class DeportistaAdminController {

	private final DeportistaAdminService servicio;

	public DeportistaAdminController(DeportistaAdminService servicio) {
		this.servicio = servicio;
	}

	@GetMapping
	public Pagina<DeportistaResumen> listar(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) String estado,
			@RequestParam(defaultValue = "") String busqueda,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + Pagina.TAMANIO_POR_DEFECTO) int size) {
		return servicio.listar(UsuarioAutenticado.desde(jwt), FiltroEstado.de(estado, FiltroEstado.ACTIVOS), busqueda,
				Pagina.pedir(page, size));
	}

	@PostMapping
	public ResponseEntity<DeportistaDetalle> crear(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody DeportistaSolicitud solicitud, HttpServletRequest request) {
		DeportistaDetalle creado = servicio.crear(UsuarioAutenticado.desde(jwt), solicitud, DatosSolicitud.de(request));
		return ResponseEntity.created(URI.create("/api/admin/deportistas/" + creado.id())).body(creado);
	}

	@GetMapping("/{id}")
	public DeportistaDetalle obtener(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return servicio.obtener(UsuarioAutenticado.desde(jwt), id);
	}

	@PutMapping("/{id}")
	public DeportistaDetalle actualizar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody DeportistaSolicitud solicitud, HttpServletRequest request) {
		return servicio.actualizar(UsuarioAutenticado.desde(jwt), id, solicitud, DatosSolicitud.de(request));
	}

	@PostMapping("/{id}/activar")
	public DeportistaDetalle activar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			HttpServletRequest request) {
		return servicio.activar(UsuarioAutenticado.desde(jwt), id, DatosSolicitud.de(request));
	}

	@PostMapping("/{id}/desactivar")
	public DeportistaDetalle desactivar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			HttpServletRequest request) {
		return servicio.desactivar(UsuarioAutenticado.desde(jwt), id, DatosSolicitud.de(request));
	}
}
