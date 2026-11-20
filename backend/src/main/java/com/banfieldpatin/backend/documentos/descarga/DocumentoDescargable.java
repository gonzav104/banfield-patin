package com.banfieldpatin.backend.documentos.descarga;

/**
 * Registro interno autorizado para descargar: destino, tipo y manifiesto de la operación publicada.
 * {@code sha256} es null si el documento no tiene una operación publicada que lo respalde. Nunca se
 * serializa ni se imprime.
 */
record DocumentoDescargable(String tipo, String bucket, String clave, String mime, long tamanioBytes, byte[] sha256) {
	@Override
	public String toString() { return "DocumentoDescargable[privado]"; }
}
