package com.banfieldpatin.backend.deportistas;

/**
 * Enmascarado del DNI para los listados de ADMIN (O8, D6): se muestran solo los ULTIMOS TRES digitos y el resto se reemplaza
 * por {@code •} (U+2022), conservando el largo original (por ejemplo, {@code 30111322} pasa a {@code •••••322}). No valida ni
 * normaliza (el DNI ya se guarda normalizado, solo digitos) y no hay forma de revertirlo. Un valor de tres caracteres o menos
 * se enmascara completo, para no devolver nunca mas de lo debido; {@code null} sigue siendo {@code null} (el DNI del tutor es
 * opcional). Se usa SOLO al armar DTO de listado: la entidad, el detalle individual y las consultas por DNI completo no lo
 * usan, y los procesos federativos leen el DNI completo en el servidor, nunca de un DTO de listado.
 */
public final class EnmascaradoDni {

	private static final char OCULTO = '•';
	private static final int VISIBLES = 3;

	private EnmascaradoDni() {
	}

	public static String de(String dni) {
		if (dni == null) {
			return null;
		}
		int largo = dni.length();
		int ocultos = Math.max(largo - VISIBLES, 0);
		if (largo <= VISIBLES) {
			ocultos = largo;
		}
		return String.valueOf(OCULTO).repeat(ocultos) + dni.substring(ocultos);
	}
}
