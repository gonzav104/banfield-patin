package com.banfieldpatin.backend.compartido.auditoria;

/** Enmascara un email para auditoria: "juan@dominio.com" -> "j***@d***.com". */
public final class MascaraEmail {

	private MascaraEmail() {
	}

	public static String enmascarar(String email) {
		if (email == null || email.isBlank()) {
			return "***";
		}
		int arroba = email.indexOf('@');
		if (arroba <= 0 || arroba == email.length() - 1) {
			return email.charAt(0) + "***";
		}
		String dominio = email.substring(arroba + 1);
		int punto = dominio.indexOf('.');
		String sufijo = punto > 0 ? dominio.substring(punto) : "";
		return email.charAt(0) + "***@" + dominio.charAt(0) + "***" + sufijo;
	}
}
