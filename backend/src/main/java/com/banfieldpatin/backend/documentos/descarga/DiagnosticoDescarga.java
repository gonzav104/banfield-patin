package com.banfieldpatin.backend.documentos.descarga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.banfieldpatin.backend.documentos.almacen.FalloAlmacenDocumentos;

/**
 * Diagnóstico de la descarga: sólo la fase, la clase simple de la causa y un código saneado. Nunca
 * bytes, SHA, claves, rutas, nombres ni identificadores de personas o escuelas.
 */
final class DiagnosticoDescarga {
	private static final Logger log = LoggerFactory.getLogger(DiagnosticoDescarga.class);

	private DiagnosticoDescarga() {
	}

	static void fallo(String fase, Throwable causa) {
		try {
			log.warn("Fallo en la descarga documental: fase={} causa={} codigo={}", fase,
					causa == null ? "-" : causa.getClass().getSimpleName(),
					causa instanceof FalloAlmacenDocumentos f ? f.codigo().name() : "-");
		} catch (RuntimeException ex) {
			/* El diagnóstico nunca altera el resultado de la descarga. */
		}
	}
}
