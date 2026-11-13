package com.banfieldpatin.backend.familias.portal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.time.Year;
import java.time.ZoneId;

/**
 * Edad de un deportista para las respuestas del portal de FAMILIA: la fecha de nacimiento exacta no sale del servidor, solo
 * los anios cumplidos. UNICA regla de edad del portal (la usan la ficha y el listado):
 * <ul>
 * <li>"hoy" es la fecha calendario en {@link #ZONA_ESCUELA}: el {@link Clock} del sistema es UTC y, sin zona, la edad
 * cambiaria unas horas antes del cumpleanios real;</li>
 * <li>quien nacio un 29 de febrero cumple el 1 de marzo en los anios sin 29 de febrero;</li>
 * <li>sin dato fiable no se inventa: {@code null} si la fecha falta, es futura o la edad supera {@link #EDAD_MAXIMA}.</li>
 * </ul>
 */
public final class EdadDeportista {

	/** Zona horaria de la escuela (unica escuela, San Pedro, Argentina). */
	public static final ZoneId ZONA_ESCUELA = ZoneId.of("America/Argentina/Buenos_Aires");

	/** Por encima de esta edad el dato se considera un error de carga y no se muestra. */
	public static final int EDAD_MAXIMA = 120;

	private EdadDeportista() {
	}

	/** Edad al dia de hoy segun el reloj dado, en la zona de la escuela. */
	public static Integer calcular(LocalDate nacimiento, Clock reloj) {
		return calcular(nacimiento, LocalDate.now(reloj.withZone(ZONA_ESCUELA)));
	}

	/** Anios cumplidos de {@code nacimiento} a la fecha {@code hoy}; {@code null} sin dato fiable. */
	public static Integer calcular(LocalDate nacimiento, LocalDate hoy) {
		if (nacimiento == null || nacimiento.isAfter(hoy)) {
			return null;
		}
		int anios = hoy.getYear() - nacimiento.getYear();
		if (hoy.isBefore(cumpleanios(nacimiento, hoy.getYear()))) {
			anios--;
		}
		return anios > EDAD_MAXIMA ? null : anios;
	}

	private static LocalDate cumpleanios(LocalDate nacimiento, int anio) {
		if (nacimiento.getMonth() == Month.FEBRUARY && nacimiento.getDayOfMonth() == 29 && !Year.isLeap(anio)) {
			return LocalDate.of(anio, Month.MARCH, 1);
		}
		return nacimiento.withYear(anio);
	}
}
