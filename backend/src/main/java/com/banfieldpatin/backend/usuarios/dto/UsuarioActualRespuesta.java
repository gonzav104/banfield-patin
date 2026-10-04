package com.banfieldpatin.backend.usuarios.dto;

import java.util.UUID;

import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.Usuario;

/**
 * Identidad minima expuesta al cliente: sin hash ni tokens. {@code mfaPendiente} indica que la sesion es de un ADMIN
 * que aun debe completar el segundo factor; {@code mfaEnrolado} que ya confirmo uno (decide si el frontend muestra
 * "enrolar" o "ingresar codigo"). Ambos son false para FAMILIA.
 */
public record UsuarioActualRespuesta(UUID id, String nombre, String apellido, String email, Rol rol,
		UUID escuelaId, UUID familiaId, boolean mfaPendiente, boolean mfaEnrolado) {

	public UsuarioActualRespuesta(UUID id, String nombre, String apellido, String email, Rol rol, UUID escuelaId,
			UUID familiaId) {
		this(id, nombre, apellido, email, rol, escuelaId, familiaId, false, false);
	}

	public static UsuarioActualRespuesta de(Usuario u) {
		return new UsuarioActualRespuesta(u.getId(), u.getNombre(), u.getApellido(), u.getEmail(), u.getRol(),
				u.getEscuelaId(), u.getFamiliaId(), false, u.getRol() == Rol.ADMIN && u.isMfaHabilitado());
	}

	public UsuarioActualRespuesta conMfaPendiente(boolean pendiente) {
		return new UsuarioActualRespuesta(id, nombre, apellido, email, rol, escuelaId, familiaId, pendiente,
				mfaEnrolado);
	}
}
