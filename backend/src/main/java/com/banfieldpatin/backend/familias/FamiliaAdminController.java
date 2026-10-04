package com.banfieldpatin.backend.familias;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.dto.FamiliaResumen;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/** Listado minimo de familias activas de la escuela del ADMIN (la escuela sale del JWT, nunca del cliente). */
@RestController
@RequestMapping("/api/admin/familias")
public class FamiliaAdminController {

	private static final int BUSQUEDA_MAX = 100;

	private final FamiliaRepository familias;

	public FamiliaAdminController(FamiliaRepository familias) {
		this.familias = familias;
	}

	@GetMapping
	public Pagina<FamiliaResumen> listar(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "") String busqueda,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + Pagina.TAMANIO_POR_DEFECTO) int size) {
		UsuarioAutenticado admin = UsuarioAutenticado.desde(jwt);
		return Pagina.de(
				familias.buscarActivas(admin.escuelaId(), patron(busqueda), Pagina.pedir(page, size)),
				f -> new FamiliaResumen(f.getId(), f.getNombreReferencia()));
	}

	/** Recorta y escapa los comodines de LIKE para que la busqueda sea siempre "contiene" literal. */
	static String patron(String busqueda) {
		String limpio = busqueda.strip();
		if (limpio.length() > BUSQUEDA_MAX) {
			limpio = limpio.substring(0, BUSQUEDA_MAX);
		}
		return limpio.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}
}
