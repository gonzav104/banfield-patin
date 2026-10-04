package com.banfieldpatin.backend.usuarios.mfa.dto;

/**
 * Datos para registrar el factor en la aplicacion autenticadora. Se devuelven una sola vez (Cache-Control: no-store)
 * y nunca se registran en logs ni auditoria.
 */
public record EnrolamientoMfaRespuesta(String otpauthUri, String secretoBase32) {

	@Override
	public String toString() {
		return "EnrolamientoMfaRespuesta[otpauthUri=***, secretoBase32=***]";
	}
}
