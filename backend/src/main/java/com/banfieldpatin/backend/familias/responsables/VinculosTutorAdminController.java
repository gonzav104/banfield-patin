package com.banfieldpatin.backend.familias.responsables;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.validation.Valid;

@RestController
public class VinculosTutorAdminController {

	private static final String RUTA = "/api/admin/deportistas/{deportistaId}/vinculos-tutores";

	private final VinculosTutorConsultaAdmin consultas;
	private final ResponsablesAdminService responsables;

	public VinculosTutorAdminController(VinculosTutorConsultaAdmin consultas, ResponsablesAdminService responsables) {
		this.consultas = consultas;
		this.responsables = responsables;
	}

	@GetMapping(RUTA)
	public Pagina<VinculoTutorRespuesta> listar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId,
			@RequestParam(defaultValue = "false") boolean historial, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return consultas.listar(UsuarioAutenticado.desde(jwt), deportistaId, historial, page, size);
	}

	/**
	 * 201 con la vigencia: la creada o, si la {@code Idempotency-Key} repite una solicitud ya procesada, la original (aunque
	 * esté cerrada). Sin {@code Location}: no hay lectura individual de una vigencia.
	 */
	@PostMapping(RUTA)
	@ResponseStatus(HttpStatus.CREATED)
	public VinculoTutorRespuesta crear(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId,
			@RequestHeader("Idempotency-Key") UUID idempotencia, @Valid @RequestBody ResponsableAlta solicitud) {
		return responsables.crear(UsuarioAutenticado.desde(jwt), deportistaId, idempotencia, solicitud).respuesta();
	}

	/** Reemplaza el tipo declarado de una vigencia abierta; es lo único editable. */
	@PutMapping(RUTA + "/{vigenciaId}")
	public VinculoTutorRespuesta actualizarTipo(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId,
			@PathVariable UUID vigenciaId, @Valid @RequestBody ResponsableTipo solicitud) {
		return responsables.actualizarTipo(UsuarioAutenticado.desde(jwt), deportistaId, vigenciaId, solicitud);
	}

	@PostMapping(RUTA + "/{vigenciaId}/revocar")
	public VinculoTutorRespuesta revocar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId,
			@PathVariable UUID vigenciaId, @Valid @RequestBody ResponsableRevision solicitud) {
		return responsables.revocar(UsuarioAutenticado.desde(jwt), deportistaId, vigenciaId, solicitud);
	}
}
