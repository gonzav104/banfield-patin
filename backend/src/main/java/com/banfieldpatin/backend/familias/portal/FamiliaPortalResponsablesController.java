package com.banfieldpatin.backend.familias.portal;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.familias.portal.dto.ResponsablesDeDeportista;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Responsables vigentes de un deportista propio para la FAMILIA, SOLO LECTURA (la cadena de seguridad exige el rol FAMILIA en
 * /api/familia/**). Ninguna ruta muta. Escuela y familia salen del JWT; parámetros como {@code familiaId} o {@code escuelaId}
 * se ignoran. Sin parámetros ni filtros: no hay historial ni paginación que exponer.
 */
@RestController
@RequestMapping("/api/familia")
public class FamiliaPortalResponsablesController {

	private final FamiliaPortalResponsablesService servicio;

	public FamiliaPortalResponsablesController(FamiliaPortalResponsablesService servicio) {
		this.servicio = servicio;
	}

	@GetMapping("/deportistas/{deportistaId}/responsables")
	public ResponsablesDeDeportista responsables(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId) {
		return servicio.responsables(UsuarioAutenticado.desde(jwt), deportistaId);
	}
}
