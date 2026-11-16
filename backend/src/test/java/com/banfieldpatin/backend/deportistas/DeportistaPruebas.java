package com.banfieldpatin.backend.deportistas;

import com.banfieldpatin.backend.deportistas.dto.DeportistaActualizacion;
import com.banfieldpatin.backend.deportistas.dto.DeportistaSolicitud;

/** Auxiliares de prueba: el reemplazo completo lleva los mismos datos que el alta mas la revision observada. */
public final class DeportistaPruebas {

	private DeportistaPruebas() {
	}

	public static DeportistaActualizacion conRevision(DeportistaSolicitud s, long version) {
		return new DeportistaActualizacion(s.dni(), s.nombre(), s.apellido(), s.cuil(), s.fechaNacimiento(),
				s.nacionalidad(), s.domicilio(), s.otrosDatosDomicilio(), s.localidad(), s.partido(), s.codigoPostal(),
				s.telefonoContacto(), s.emailFederativo(), version);
	}
}
