package com.banfieldpatin.backend.deportistas;

/**
 * Normalizacion y validacion de DNI y CUIL (REQ-DEP-01, REQ-DEP-02). Solo se valida en la aplicacion (sin CHECK en la
 * base). Se comparte entre tutores y deportistas. Ningun metodo registra ni devuelve el valor en mensajes de error.
 */
public final class DocumentoIdentidad {

	private static final int[] PESOS_CUIL = { 5, 4, 3, 2, 7, 6, 5, 4, 3, 2 };

	private DocumentoIdentidad() {
	}

	/** Quita puntos y espacios en blanco. Null permanece null. */
	public static String normalizarDni(String crudo) {
		return crudo == null ? null : crudo.replaceAll("[.\\s\\p{Z}]", "");
	}

	/** Valido si, sin puntos ni espacios, son entre 7 y 9 digitos ASCII. */
	public static boolean dniValido(String crudo) {
		String dni = normalizarDni(crudo);
		return dni != null && dni.matches("[0-9]{7,9}");
	}

	/** Quita puntos, guiones y espacios en blanco. Null permanece null. */
	public static String normalizarCuil(String crudo) {
		return crudo == null ? null : crudo.replaceAll("[.\\-\\s\\p{Z}]", "");
	}

	/**
	 * Valido si, sin separadores, son 11 digitos y el ultimo coincide con el digito verificador: pesos
	 * 5,4,3,2,7,6,5,4,3,2 sobre los diez primeros, r = 11 - (suma % 11); r == 11 equivale a 0 y r == 10 no tiene digito
	 * posible (CUIL invalido). No hay lista de prefijos ni cruce con el DNI.
	 */
	public static boolean cuilValido(String crudo) {
		String cuil = normalizarCuil(crudo);
		if (cuil == null || !cuil.matches("[0-9]{11}")) {
			return false;
		}
		int suma = 0;
		for (int i = 0; i < PESOS_CUIL.length; i++) {
			suma += (cuil.charAt(i) - '0') * PESOS_CUIL[i];
		}
		int resto = 11 - (suma % 11);
		if (resto == 10) {
			return false;
		}
		int esperado = resto == 11 ? 0 : resto;
		return cuil.charAt(10) - '0' == esperado;
	}
}
