package com.banfieldpatin.backend.familias;

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

import com.banfieldpatin.backend.compartido.web.Busqueda;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.FiltroEstado;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.dto.FamiliaAdminResumen;
import com.banfieldpatin.backend.familias.dto.FamiliaDetalle;
import com.banfieldpatin.backend.familias.dto.FamiliaSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Gestion de familias para ADMIN (la cadena de seguridad protege /api/admin/**). La escuela y el actor salen del JWT.
 * El listado legacy {@code GET /api/admin/familias} (solo activas, FamiliaResumen) sigue en
 * {@link FamiliaAdminController} sin cambios; el listado completo vive en {@code /listado} (el literal gana a
 * {@code /{id}}).
 */
@RestController
@RequestMapping("/api/admin/familias")
public class FamiliaGestionAdminController {

	private final FamiliaAdminService servicio;

	public FamiliaGestionAdminController(FamiliaAdminService servicio) {
		this.servicio = servicio;
	}

	@GetMapping("/listado")
	public Pagina<FamiliaAdminResumen> listar(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) String estado,
			@RequestParam(defaultValue = "") String busqueda,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + Pagina.TAMANIO_POR_DEFECTO) int size) {
		return servicio.listar(UsuarioAutenticado.desde(jwt), FiltroEstado.de(estado, FiltroEstado.TODOS),
				Busqueda.patronLike(busqueda), Pagina.pedir(page, size));
	}

	@PostMapping
	public ResponseEntity<FamiliaDetalle> crear(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody FamiliaSolicitud solicitud, HttpServletRequest request) {
		FamiliaDetalle creada = servicio.crear(UsuarioAutenticado.desde(jwt), solicitud, DatosSolicitud.de(request));
		return ResponseEntity.created(URI.create("/api/admin/familias/" + creada.id())).body(creada);
	}

	@GetMapping("/{id}")
	public FamiliaDetalle obtener(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return servicio.obtener(UsuarioAutenticado.desde(jwt), id);
	}

	@PutMapping("/{id}")
	public FamiliaDetalle actualizar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody FamiliaSolicitud solicitud, HttpServletRequest request) {
		return servicio.actualizar(UsuarioAutenticado.desde(jwt), id, solicitud, DatosSolicitud.de(request));
	}

	@PostMapping("/{id}/activar")
	public FamiliaDetalle activar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			HttpServletRequest request) {
		return servicio.activar(UsuarioAutenticado.desde(jwt), id, DatosSolicitud.de(request));
	}

	@PostMapping("/{id}/desactivar")
	public FamiliaDetalle desactivar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			HttpServletRequest request) {
		return servicio.desactivar(UsuarioAutenticado.desde(jwt), id, DatosSolicitud.de(request));
	}
}
