package com.banfieldpatin.backend.familias.tutores.verificacion;

import java.util.UUID;

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
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.EstadoVerificacion;
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.LoteVerificacion;
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.ResolucionVerificacion;
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.ResultadoLote;
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.TutorPorVerificar;
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.VerificacionResultado;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Bandeja de tutores por verificar (U21-BIS C2). ADMIN con MFA completo ({@code /api/admin/**}), CSRF en las escrituras; la
 * escuela sale de la sesión.
 */
@RestController
@RequestMapping("/api/admin/tutores")
public class VerificacionTutoresController {

	private final VerificacionTutoresService servicio;

	public VerificacionTutoresController(VerificacionTutoresService servicio) {
		this.servicio = servicio;
	}

	@GetMapping("/por-verificar")
	public Pagina<TutorPorVerificar> porVerificar(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) EstadoVerificacion estado, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + Pagina.TAMANIO_POR_DEFECTO) int size) {
		return servicio.listar(UsuarioAutenticado.desde(jwt), estado, Pagina.pedir(page, size));
	}

	@PostMapping("/{id}/verificacion")
	public VerificacionResultado resolver(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody ResolucionVerificacion solicitud, HttpServletRequest request) {
		return servicio.resolver(UsuarioAutenticado.desde(jwt), id, solicitud, DatosSolicitud.de(request));
	}

	@PostMapping("/verificacion-lote")
	public ResultadoLote verificarLote(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LoteVerificacion solicitud,
			HttpServletRequest request) {
		return servicio.verificarLote(UsuarioAutenticado.desde(jwt), solicitud, DatosSolicitud.de(request));
	}
}
