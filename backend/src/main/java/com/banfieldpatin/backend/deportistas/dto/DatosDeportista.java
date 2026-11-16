package com.banfieldpatin.backend.deportistas.dto;

import java.time.LocalDate;

/**
 * Los trece datos personales permanentes de un deportista tal como los aceptan el alta y el reemplazo completo. Lo
 * implementan {@link DeportistaSolicitud} (alta) y {@link DeportistaActualizacion} (edicion): el servicio los trata igual y
 * la normalizacion vive en un solo lugar.
 */
public interface DatosDeportista {

	String dni();

	String nombre();

	String apellido();

	String cuil();

	LocalDate fechaNacimiento();

	String nacionalidad();

	String domicilio();

	String otrosDatosDomicilio();

	String localidad();

	String partido();

	String codigoPostal();

	String telefonoContacto();

	String emailFederativo();

	/** Todo se recorta; un opcional en blanco pasa a null; el email se guarda en minusculas. */
	static String recortar(String valor) {
		return valor == null ? null : valor.strip();
	}

	static String opcional(String valor) {
		String limpio = recortar(valor);
		return limpio == null || limpio.isEmpty() ? null : limpio;
	}

	static String emailNormalizado(String valor) {
		String limpio = opcional(valor);
		return limpio == null ? null : limpio.toLowerCase(java.util.Locale.ROOT);
	}
}
