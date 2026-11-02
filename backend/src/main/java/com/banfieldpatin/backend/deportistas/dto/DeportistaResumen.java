package com.banfieldpatin.backend.deportistas.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.banfieldpatin.backend.deportistas.Deportista;
import com.banfieldpatin.backend.deportistas.EnmascaradoDni;

/**
 * Fila del listado administrativo de deportistas. Sin escuelaId y con el DNI ENMASCARADO (O8): jamas el completo, que solo
 * viaja en el detalle individual ({@link DeportistaDetalle}).
 */
public record DeportistaResumen(UUID id, String nombre, String apellido, String dniEnmascarado, LocalDate fechaNacimiento,
		boolean activo) {

	public static DeportistaResumen de(Deportista d) {
		return new DeportistaResumen(d.getId(), d.getNombre(), d.getApellido(), EnmascaradoDni.de(d.getDni()), d.getFechaNacimiento(),
				d.isActivo());
	}

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "DeportistaResumen[id=" + id + "]";
	}
}
