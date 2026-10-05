package com.banfieldpatin.backend.deportistas.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.banfieldpatin.backend.deportistas.Deportista;

/** Fila del listado administrativo de deportistas. Sin escuelaId. */
public record DeportistaResumen(UUID id, String nombre, String apellido, String dni, LocalDate fechaNacimiento,
		boolean activo) {

	public static DeportistaResumen de(Deportista d) {
		return new DeportistaResumen(d.getId(), d.getNombre(), d.getApellido(), d.getDni(), d.getFechaNacimiento(),
				d.isActivo());
	}

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "DeportistaResumen[id=" + id + "]";
	}
}
