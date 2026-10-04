package com.banfieldpatin.backend.usuarios.dto;

import java.util.UUID;

import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.Usuario;

/** Identidad minima expuesta al cliente: sin hash, tokens ni flags internos. */
public record UsuarioActualRespuesta(UUID id, String nombre, String apellido, String email, Rol rol,
		UUID escuelaId, UUID familiaId) {

	public static UsuarioActualRespuesta de(Usuario u) {
		return new UsuarioActualRespuesta(u.getId(), u.getNombre(), u.getApellido(), u.getEmail(), u.getRol(),
				u.getEscuelaId(), u.getFamiliaId());
	}
}
