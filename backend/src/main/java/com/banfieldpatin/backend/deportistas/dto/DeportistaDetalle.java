package com.banfieldpatin.backend.deportistas.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.banfieldpatin.backend.deportistas.Deportista;

/** Datos permanentes y estado de un deportista para un ADMIN. Sin escuelaId, sin vinculos ni datos de temporada. */
public record DeportistaDetalle(UUID id, String nombre, String apellido, String dni, String cuil,
		LocalDate fechaNacimiento, String nacionalidad, String domicilio, String otrosDatosDomicilio, String localidad,
		String partido, String codigoPostal, String telefonoContacto, String emailFederativo, boolean activo) {

	public static DeportistaDetalle de(Deportista d) {
		return new DeportistaDetalle(d.getId(), d.getNombre(), d.getApellido(), d.getDni(), d.getCuil(),
				d.getFechaNacimiento(), d.getNacionalidad(), d.getDomicilio(), d.getOtrosDatosDomicilio(),
				d.getLocalidad(), d.getPartido(), d.getCodigoPostal(), d.getTelefonoContacto(), d.getEmailFederativo(),
				d.isActivo());
	}

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "DeportistaDetalle[id=" + id + "]";
	}
}
