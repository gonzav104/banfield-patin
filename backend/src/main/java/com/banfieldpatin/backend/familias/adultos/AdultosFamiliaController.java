package com.banfieldpatin.backend.familias.adultos;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.adultos.dto.AdultoActualizacion;
import com.banfieldpatin.backend.familias.adultos.dto.AdultoAlta;
import com.banfieldpatin.backend.familias.adultos.dto.AdultoDeFamilia;
import com.banfieldpatin.backend.familias.adultos.dto.AdultosRespuesta;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Otros adultos de la propia familia (U21-BIS C2). La cadena de seguridad exige el rol FAMILIA en {@code /api/familia/**};
 * escuela, familia y cuenta salen del token. Son datos personales: nunca se cachean.
 */
@RestController
@RequestMapping("/api/familia/tutores")
public class AdultosFamiliaController {

	private final AdultosFamiliaService servicio;

	public AdultosFamiliaController(AdultosFamiliaService servicio) {
		this.servicio = servicio;
	}

	@GetMapping
	public ResponseEntity<AdultosRespuesta> listar(@AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(servicio.listar(UsuarioAutenticado.desde(jwt)));
	}

	/** 201 con el adulto: el creado o, si la {@code Idempotency-Key} repite un alta con los mismos datos, el original. */
	@PostMapping
	public ResponseEntity<AdultoDeFamilia> crear(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") UUID idempotencia, @Valid @RequestBody AdultoAlta solicitud,
			HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(servicio.crear(UsuarioAutenticado.desde(jwt), idempotencia, solicitud, DatosSolicitud.de(request)));
	}

	@PutMapping("/{id}")
	public ResponseEntity<AdultoDeFamilia> actualizar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody AdultoActualizacion solicitud, HttpServletRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(servicio.actualizar(UsuarioAutenticado.desde(jwt), id, solicitud, DatosSolicitud.de(request)));
	}
}
