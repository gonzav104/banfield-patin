package com.banfieldpatin.backend.familias.tutores.dto;

import java.util.Locale;

/**
 * Los seis datos personales de un tutor tal como los aceptan el alta y el reemplazo completo. Lo implementan
 * {@link TutorSolicitud} (alta) y {@link TutorActualizacion} (edicion): el servicio los trata igual y la normalizacion
 * vive en un solo lugar.
 */
public interface DatosTutor {

	String nombre();

	String apellido();

	String dni();

	String telefono();

	String email();

	String parentesco();

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
		return limpio == null ? null : limpio.toLowerCase(Locale.ROOT);
	}
}
