package com.banfieldpatin.backend.temporadas;

import java.net.URI;
import java.util.UUID;

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
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.dto.TemporadaRespuesta;
import com.banfieldpatin.backend.temporadas.dto.TemporadaSolicitud;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Temporadas para ADMIN (la cadena de seguridad protege /api/admin/**). La escuela y el actor salen del JWT, nunca del
 * cliente. Sin PUT, DELETE ni desactivar: el anio es la identidad funcional, no se borra y la activa solo cambia al activar otra.
 */
@RestController
@RequestMapping("/api/admin/temporadas")
public class TemporadaAdminController {

	private final TemporadaAdminService servicio;

	public TemporadaAdminController(TemporadaAdminService servicio) {
		this.servicio = servicio;
	}

	@GetMapping
	public Pagina<TemporadaRespuesta> listar(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + Pagina.TAMANIO_POR_DEFECTO) int size) {
		return servicio.listar(UsuarioAutenticado.desde(jwt), Pagina.pedir(page, size));
	}

	@PostMapping
	public ResponseEntity<TemporadaRespuesta> crear(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody TemporadaSolicitud solicitud, HttpServletRequest request) {
		TemporadaRespuesta creada = servicio.crear(UsuarioAutenticado.desde(jwt), solicitud, DatosSolicitud.de(request));
		return ResponseEntity.created(URI.create("/api/admin/temporadas/" + creada.id())).body(creada);
	}

	@GetMapping("/{id}")
	public TemporadaRespuesta obtener(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return servicio.obtener(UsuarioAutenticado.desde(jwt), id);
	}

	@PostMapping("/{id}/activar")
	public TemporadaRespuesta activar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			HttpServletRequest request) {
		return servicio.activar(UsuarioAutenticado.desde(jwt), id, DatosSolicitud.de(request));
	}
}
