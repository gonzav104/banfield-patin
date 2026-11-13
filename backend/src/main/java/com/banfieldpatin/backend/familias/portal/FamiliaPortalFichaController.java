package com.banfieldpatin.backend.familias.portal;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.familias.portal.dto.FichaDeportistaDeFamilia;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Ficha minima de un deportista para la FAMILIA, SOLO LECTURA (la cadena de seguridad exige el rol FAMILIA en
 * /api/familia/**). Ninguna ruta muta: cualquier otro metodo da 405 METODO_NO_PERMITIDO (con CSRF) o 403 CSRF_INVALIDO (sin
 * el). La escuela y la familia salen del JWT, nunca de la solicitud: parametros como {@code familiaId} o {@code escuelaId}
 * se ignoran. Sin parametros ni filtros: menos superficie es menos filtracion.
 */
@RestController
@RequestMapping("/api/familia")
public class FamiliaPortalFichaController {

	private final FamiliaPortalFichaService servicio;

	public FamiliaPortalFichaController(FamiliaPortalFichaService servicio) {
		this.servicio = servicio;
	}

	@GetMapping("/deportistas/{deportistaId}/ficha")
	public FichaDeportistaDeFamilia ficha(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId) {
		return servicio.ficha(UsuarioAutenticado.desde(jwt), deportistaId);
	}
}
