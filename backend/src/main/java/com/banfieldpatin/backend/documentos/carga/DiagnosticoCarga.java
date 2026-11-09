package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.almacen.FalloAlmacenDocumentos;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnóstico de fallos de la carga. Registra únicamente el identificador aleatorio de la
 * operación, la fase, la clase de la causa y un código saneado (el de {@link ExcepcionNegocio} o el
 * de {@link FalloAlmacenDocumentos}). Nunca el mensaje de la excepción, su traza, rutas, nombres,
 * SHA ni identificadores de personas, familias o escuelas.
 */
final class DiagnosticoCarga {
  private static final Logger log = LoggerFactory.getLogger(DiagnosticoCarga.class);

  private DiagnosticoCarga() {}

  static void fallo(UUID operacion, String fase, Throwable causa) {
    try {
      log.warn(
          "Fallo en la carga documental: operacion={} fase={} causa={} codigo={}",
          operacion == null ? "-" : operacion,
          fase,
          causa.getClass().getSimpleName(),
          codigo(causa));
    } catch (RuntimeException ex) {
      /* El diagnóstico nunca altera el resultado de la carga. */
    }
  }

  /** Un reintento adquirió una operación cuya reserva había vencido. */
  static void retomada(UUID operacion, String estado) {
    try {
      log.info(
          "Operación de carga retomada tras vencer su reserva: operacion={} estado={}",
          operacion,
          estado);
    } catch (RuntimeException ex) {
      /* El diagnóstico nunca altera el resultado de la carga. */
    }
  }

  private static String codigo(Throwable causa) {
    if (causa instanceof ExcepcionNegocio e) return e.getCodigo();
    if (causa instanceof FalloAlmacenDocumentos f) return f.codigo().name();
    return "-";
  }
}
