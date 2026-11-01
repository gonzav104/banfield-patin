package com.banfieldpatin.backend.temporadas;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaActualizacion;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaRespuesta;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaSolicitud;
import com.banfieldpatin.backend.temporadas.dto.PlantelRespuesta;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Datos deportivos por temporada para ADMIN (la cadena de seguridad protege /api/admin/**). La escuela y el actor salen del
 * JWT, nunca del cliente. La identidad de una fila es el par (deportistaId, temporadaId) de la ruta. Sin DELETE ni PATCH:
 * un deportista que no compite una temporada pasa a INACTIVO, la fila no se borra.
 */
@RestController
public class DeportistaTemporadaAdminController {

	private static final String DEL_DEPORTISTA = "/api/admin/deportistas/{deportistaId}/temporadas";

	private final DeportistaTemporadaAdminService servicio;

	public DeportistaTemporadaAdminController(DeportistaTemporadaAdminService servicio) {
		this.servicio = servicio;
	}

	@GetMapping(DEL_DEPORTISTA)
	public List<DeportistaTemporadaRespuesta> historial(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID deportistaId) {
		return servicio.historial(UsuarioAutenticado.desde(jwt), deportistaId);
	}

	@PostMapping(DEL_DEPORTISTA)
	public ResponseEntity<DeportistaTemporadaRespuesta> crear(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID deportistaId, @Valid @RequestBody DeportistaTemporadaSolicitud solicitud,
			HttpServletRequest request) {
		DeportistaTemporadaRespuesta creada = servicio.crear(UsuarioAutenticado.desde(jwt), deportistaId, solicitud,
				DatosSolicitud.de(request));
		return ResponseEntity.created(URI.create("/api/admin/deportistas/" + deportistaId + "/temporadas/"
				+ creada.temporadaId())).body(creada);
	}

	@GetMapping(DEL_DEPORTISTA + "/{temporadaId}")
	public DeportistaTemporadaRespuesta obtener(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId,
			@PathVariable UUID temporadaId) {
		return servicio.obtener(UsuarioAutenticado.desde(jwt), deportistaId, temporadaId);
	}

	@PutMapping(DEL_DEPORTISTA + "/{temporadaId}")
	public DeportistaTemporadaRespuesta actualizar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId,
			@PathVariable UUID temporadaId, @Valid @RequestBody DeportistaTemporadaActualizacion actualizacion,
			HttpServletRequest request) {
		return servicio.actualizar(UsuarioAutenticado.desde(jwt), deportistaId, temporadaId, actualizacion,
				DatosSolicitud.de(request));
	}

	@GetMapping("/api/admin/temporadas/{temporadaId}/deportistas")
	public Pagina<PlantelRespuesta> plantel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID temporadaId,
			@RequestParam(required = false) String estadoDeportivo, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + Pagina.TAMANIO_POR_DEFECTO) int size) {
		return servicio.plantel(UsuarioAutenticado.desde(jwt), temporadaId, estadoDeportivo, Pagina.pedir(page, size));
	}
}
