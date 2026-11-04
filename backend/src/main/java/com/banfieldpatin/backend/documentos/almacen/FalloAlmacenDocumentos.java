package com.banfieldpatin.backend.documentos.almacen;

/** Error interno saneado: no retiene el cuerpo, URI, cabeceras ni excepción del proveedor. */
public final class FalloAlmacenDocumentos extends RuntimeException {
	public enum Codigo { ENTRADA_INVALIDA, AUTENTICACION, CONFLICTO, RECHAZADO, RED, TIMEOUT, RESPUESTA_INESPERADA }
	private final Codigo codigo;
	private final boolean resultadoIncierto;

	FalloAlmacenDocumentos(Codigo codigo, boolean resultadoIncierto) {
		super("Almacenamiento documental: " + codigo, null, false, true);
		this.codigo = codigo;
		this.resultadoIncierto = resultadoIncierto;
	}

	public Codigo codigo() { return codigo; }
	public boolean resultadoIncierto() { return resultadoIncierto; }
}
