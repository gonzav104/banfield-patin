package com.banfieldpatin.backend.familias.portal;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.familias.portal.dto.TemporadaDeFamilia;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Datos deportivos anuales para la FAMILIA, SOLO LECTURA (la cadena de seguridad exige el rol FAMILIA en /api/familia/**).
 * Ninguna ruta muta: cualquier otro metodo da 405 METODO_NO_PERMITIDO (con CSRF) o 403 CSRF_INVALIDO (sin el). La escuela y la
 * familia salen del JWT, nunca de la solicitud: parametros como {@code familiaId} o {@code escuelaId} se ignoran. Sin
 * paginacion, filtros ni busqueda: el historial esta acotado (a lo sumo 81 anios) y menos superficie es menos filtracion.
 */
@RestController
@RequestMapping("/api/familia")
public class FamiliaPortalTemporadasController {

	private final FamiliaPortalTemporadasService servicio;

	public FamiliaPortalTemporadasController(FamiliaPortalTemporadasService servicio) {
		this.servicio = servicio;
	}

	@GetMapping("/deportistas/{deportistaId}/temporadas")
	public List<TemporadaDeFamilia> historial(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId) {
		return servicio.historial(UsuarioAutenticado.desde(jwt), deportistaId);
	}

	@GetMapping("/deportistas/{deportistaId}/temporadas/{temporadaId}")
	public TemporadaDeFamilia temporada(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId,
			@PathVariable UUID temporadaId) {
		return servicio.temporada(UsuarioAutenticado.desde(jwt), deportistaId, temporadaId);
	}
}
