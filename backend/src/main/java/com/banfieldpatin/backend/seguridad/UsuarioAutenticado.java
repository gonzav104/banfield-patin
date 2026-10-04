package com.banfieldpatin.backend.seguridad;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

import com.banfieldpatin.backend.usuarios.Rol;

/** Identidad derivada del JWT: la escuela y la familia nunca vienen del cliente. */
public record UsuarioAutenticado(UUID id, UUID escuelaId, UUID familiaId, Rol rol) {

	public static UsuarioAutenticado desde(Jwt jwt) {
		String familia = jwt.getClaimAsString(ServicioTokens.CLAIM_FAMILIA_ID);
		return new UsuarioAutenticado(
				UUID.fromString(jwt.getSubject()),
				UUID.fromString(jwt.getClaimAsString(ServicioTokens.CLAIM_ESCUELA_ID)),
				familia == null ? null : UUID.fromString(familia),
				Rol.valueOf(jwt.getClaimAsString(ServicioTokens.CLAIM_ROL)));
	}
}
