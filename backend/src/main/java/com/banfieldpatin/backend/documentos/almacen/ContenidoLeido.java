package com.banfieldpatin.backend.documentos.almacen;

/**
 * Bytes completos de un objeto leído del almacenamiento, sin interpretar ni verificar. Para no
 * duplicar memoria el arreglo no se copia: pertenece al llamador, que no debe registrarlo, persistirlo
 * en disco ni conservarlo más allá de la entrega. Nunca se imprime su contenido.
 */
public final class ContenidoLeido {
	private final byte[] contenido;
	private final String tipoContenido;

	public ContenidoLeido(byte[] contenido, String tipoContenido) {
		if (contenido == null || tipoContenido == null) throw new IllegalArgumentException("Contenido leído inválido");
		this.contenido = contenido;
		this.tipoContenido = tipoContenido;
	}

	/** El arreglo interno, sin copia. */
	public byte[] contenido() { return contenido; }
	public long tamanioBytes() { return contenido.length; }
	/** Tipo declarado por el proveedor; informativo, no reemplaza al registrado internamente. */
	public String tipoContenido() { return tipoContenido; }
	@Override public String toString() { return "ContenidoLeido[privado]"; }
}
