package com.banfieldpatin.backend.familias.portal;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.portal.dto.DeportistaDeFamilia;
import com.banfieldpatin.backend.familias.portal.dto.DeportistaDeFamiliaDetalle;
import com.banfieldpatin.backend.familias.portal.dto.MiFamiliaRespuesta;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Portal de FAMILIA, SOLO LECTURA (la cadena de seguridad exige el rol FAMILIA en /api/familia/**). Ninguna ruta muta:
 * cualquier otro metodo da 405 METODO_NO_PERMITIDO (con CSRF) o 403 CSRF_INVALIDO (sin el). La escuela y la familia salen
 * del JWT, nunca de la solicitud: parametros como {@code familiaId} o {@code escuelaId} se ignoran.
 */
@RestController
@RequestMapping("/api/familia")
public class FamiliaPortalController {

	private final FamiliaPortalService servicio;

	public FamiliaPortalController(FamiliaPortalService servicio) {
		this.servicio = servicio;
	}

	@GetMapping("/mi-familia")
	public MiFamiliaRespuesta miFamilia(@AuthenticationPrincipal Jwt jwt) {
		return servicio.miFamilia(UsuarioAutenticado.desde(jwt));
	}

	@GetMapping("/deportistas")
	public Pagina<DeportistaDeFamilia> deportistas(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + Pagina.TAMANIO_POR_DEFECTO) int size) {
		return servicio.deportistas(UsuarioAutenticado.desde(jwt), Pagina.pedir(page, size));
	}

	@GetMapping("/deportistas/{id}")
	public DeportistaDeFamiliaDetalle deportista(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return servicio.deportista(UsuarioAutenticado.desde(jwt), id);
	}
}
