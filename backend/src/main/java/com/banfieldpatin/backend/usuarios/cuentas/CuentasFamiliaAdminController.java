package com.banfieldpatin.backend.usuarios.cuentas;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.cuentas.dto.CuentaFamiliar;
import com.banfieldpatin.backend.usuarios.cuentas.dto.CuentasFamilia;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Cuentas de acceso de una familia (U21-BIS C0). Solo ADMIN con sesión completa ({@code /api/admin/**}); la escuela y el
 * actor salen del token. Desactivar y reactivar son idempotentes y requieren CSRF.
 */
@RestController
@RequestMapping("/api/admin/familias/{familiaId}/cuentas")
public class CuentasFamiliaAdminController {

	private final CuentasFamiliaAdminService servicio;

	public CuentasFamiliaAdminController(CuentasFamiliaAdminService servicio) {
		this.servicio = servicio;
	}

	@GetMapping
	public CuentasFamilia listar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familiaId) {
		return servicio.listar(UsuarioAutenticado.desde(jwt), familiaId);
	}

	@PostMapping("/{cuentaId}/desactivar")
	public CuentaFamiliar desactivar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familiaId,
			@PathVariable UUID cuentaId, HttpServletRequest request) {
		return servicio.desactivar(UsuarioAutenticado.desde(jwt), familiaId, cuentaId, DatosSolicitud.de(request));
	}

	@PostMapping("/{cuentaId}/reactivar")
	public CuentaFamiliar reactivar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familiaId,
			@PathVariable UUID cuentaId, HttpServletRequest request) {
		return servicio.reactivar(UsuarioAutenticado.desde(jwt), familiaId, cuentaId, DatosSolicitud.de(request));
	}
}
