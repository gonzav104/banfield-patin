package com.banfieldpatin.backend.familias.vinculos;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculacionRespuesta;
import com.banfieldpatin.backend.familias.vinculos.dto.VincularSolicitud;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Vinculos familia-deportista para ADMIN (la cadena de seguridad protege /api/admin/**). La escuela y el actor salen del
 * JWT. No hay DELETE ni PATCH (405 METODO_NO_PERMITIDO): un vinculo se revoca, nunca se borra. Las listas de ambos lados
 * viven aqui para que familias y deportistas no dependan entre si.
 */
@RestController
@RequestMapping("/api/admin")
public class VinculoAdminController {

	private final VinculoAdminService servicio;

	public VinculoAdminController(VinculoAdminService servicio) {
		this.servicio = servicio;
	}

	@GetMapping("/familias/{familiaId}/deportistas")
	public List<VinculoRespuesta> deFamilia(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familiaId) {
		return servicio.listarDeFamilia(UsuarioAutenticado.desde(jwt), familiaId);
	}

	@GetMapping("/deportistas/{deportistaId}/familias")
	public List<VinculoRespuesta> deDeportista(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId) {
		return servicio.listarDeDeportista(UsuarioAutenticado.desde(jwt), deportistaId);
	}

	@PostMapping("/familias/{familiaId}/deportistas")
	public VinculacionRespuesta vincular(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familiaId,
			@Valid @RequestBody VincularSolicitud solicitud, HttpServletRequest request) {
		return servicio.vincular(UsuarioAutenticado.desde(jwt), familiaId, solicitud.deportistaIds(),
				DatosSolicitud.de(request));
	}

	@PostMapping("/familias/{familiaId}/deportistas/{deportistaId}/revocar")
	public VinculoRespuesta revocar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familiaId,
			@PathVariable UUID deportistaId, HttpServletRequest request) {
		return servicio.revocar(UsuarioAutenticado.desde(jwt), familiaId, deportistaId, DatosSolicitud.de(request));
	}

	@PostMapping("/familias/{familiaId}/deportistas/{deportistaId}/principal")
	public VinculoRespuesta principal(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familiaId,
			@PathVariable UUID deportistaId, HttpServletRequest request) {
		return servicio.cambiarPrincipal(UsuarioAutenticado.desde(jwt), familiaId, deportistaId,
				DatosSolicitud.de(request));
	}
}
