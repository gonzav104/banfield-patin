package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.documentos.*;
import com.banfieldpatin.backend.documentos.almacen.*;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Orquesta fases sin transacción exterior; ninguna excepción retiene respuestas o causas del
 * proveedor.
 */
public final class CargaDocumentalService {
  record Resultado(DocumentoMetadatos documento, boolean repetida) {}

  private final OperacionesCargaJdbc operaciones;
  private final PreparadorDocumento preparador;
  private final ClamAvPrivado scanner;
  private final AlmacenDocumentos almacen;
  private final Semaphore cupos;

  CargaDocumentalService(
      OperacionesCargaJdbc operaciones, CargaPropiedades p, AlmacenDocumentos almacen) {
    this.operaciones = operaciones;
    this.preparador = new PreparadorDocumento(p);
    this.scanner = new ClamAvPrivado(p);
    this.almacen = almacen;
    this.cupos = new Semaphore(p.concurrencia());
  }


  void preautorizar(ContextoCarga c, UUID atleta) {
    try {
      operaciones.preautorizar(c, atleta);
    } catch (ExcepcionNegocio ex) {
      throw ex;
    } catch (RuntimeException ex) {
      DiagnosticoCarga.fallo(null, "preautorizar", ex);
      throw ErrorCarga.indisponible();
    }
  }

  /**
   * Se invoca con el multipart ya recibido (el controlador resuelve sus partes antes): el permiso de
   * procesamiento se toma aquí, sin espera, y se libera exactamente una vez por cualquier salida.
   * Sin capacidad responde 429 de inmediato, antes de preparar temporales; no hay cola.
   */
  Resultado cargar(
      ContextoCarga c,
      UUID atleta,
      TipoDocumento tipo,
      UUID temporada,
      UUID idem,
      MultipartFile archivo,
      DatosSolicitud datos) {
    if (!cupos.tryAcquire())
      throw new ExcepcionNegocio(
          org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
          "CARGA_OCUPADA",
          "No hay capacidad de carga disponible.");
    try {
      return procesar(c, atleta, tipo, temporada, idem, archivo, datos);
    } finally {
      cupos.release();
    }
  }

  private Resultado procesar(
      ContextoCarga c,
      UUID atleta,
      TipoDocumento tipo,
      UUID temporada,
      UUID idem,
      MultipartFile archivo,
      DatosSolicitud datos) {
    OperacionesCargaJdbc.Operacion op = null;
    boolean transferencia = false;
    String fase = "preparar";
    ExcepcionNegocio resultado = null;
    try {
      if (TransactionSynchronizationManager.isActualTransactionActive())
        throw ErrorCarga.indisponible();
      try (var a = preparador.preparar(archivo)) {
        fase = "reservar";
        var reserva =
            operaciones.reservar(c, atleta, tipo, temporada, idem, almacen.prepararDestino(), a);
        if (reserva.publicada() != null) return new Resultado(reserva.publicada(), true);
        op = reserva.operacion();
        if (reserva.retomada()) DiagnosticoCarga.retomada(op.id(), op.estado());
        if (op.estado().equals("RECONCILIANDO")) {
          // Una transferencia incierta nunca se publica: la existencia no prueba integridad.
          transferencia = true;
          fase = "reconciliar";
          resultado = reconciliar(op);
          throw resultado;
        }
        if (op.estado().equals("ALMACENADA")) {
          // El objeto ya fue confirmado por Storage: se publica sin repetir la transferencia.
          transferencia = true;
          fase = "publicar";
          return new Resultado(operaciones.publicar(c, op, datos), false);
        }
        fase = "escanear";
        scanner.escanear(a.ruta);
        fase = "iniciar";
        op = operaciones.iniciar(c, op);
        transferencia = true;
        fase = "transferir";
        try (var contenido = java.nio.file.Files.newInputStream(a.ruta)) {
          almacen.crear(op.destino(), contenido, a.bytes, a.mime);
        }
        fase = "almacenada";
        op = operaciones.almacenada(op);
        fase = "publicar";
        return new Resultado(operaciones.publicar(c, op, datos), false);
      }
    } catch (ExcepcionNegocio ex) {
      if (ex == resultado) throw ex;
      // Un 409 con la operación ya reservada es una pérdida de CAS: anómalo, a diferencia de un 4xx del cliente.
      if (ex.getEstado().is5xxServerError() || op != null && ex.getEstado().value() == 409)
        DiagnosticoCarga.fallo(op == null ? null : op.id(), fase, ex);
      compensar(op, transferencia, fase, ex);
      throw ex;
    } catch (Exception ex) {
      DiagnosticoCarga.fallo(op == null ? null : op.id(), fase, ex);
      compensar(op, transferencia, fase, ex);
      throw transferencia ? ErrorCarga.incierto() : ErrorCarga.indisponible();
    }
  }

  /**
   * Resuelve una operación RECONCILIANDO cuya reserva mantiene esta solicitud. Existencia o ausencia
   * confirmadas la cierran sin publicar (409: reintentar con otra clave de idempotencia); un HEAD 404
   * no prueba que una transferencia anterior terminó, pero un objeto tardío queda huérfano y nunca
   * publicado. Un resultado incierto conserva RECONCILIANDO, libera la reserva y responde 503.
   */
  private ExcepcionNegocio reconciliar(OperacionesCargaJdbc.Operacion op) {
    InspeccionAlmacen.Estado estado;
    try {
      estado = almacen.inspeccionar(op.destino()).estado();
    } catch (RuntimeException ex) {
      estado = InspeccionAlmacen.Estado.INCIERTA;
    }
    if (estado == InspeccionAlmacen.Estado.INCIERTA) {
      DiagnosticoCarga.reconciliacion(op.id(), estado.name(), "RECONCILIANDO");
      operaciones.liberar(op);
      return ErrorCarga.incierto();
    }
    operaciones.cerrarReconciliada(op);
    DiagnosticoCarga.reconciliacion(op.id(), estado.name(), "CERRADA");
    return ErrorCarga.conflicto();
  }

  /**
   * Compensación según la fase. Antes de transferir se cierra; con la transferencia incierta se
   * marca RECONCILIANDO; con el objeto ya confirmado (publicar), un conflicto permanente cierra sin
   * publicar y un fallo transitorio conserva ALMACENADA liberando la reserva, para que el reintento
   * la publique (U4). La reconciliación resuelve su propio estado.
   */
  private void compensar(
      OperacionesCargaJdbc.Operacion op, boolean transferencia, String fase, Exception ex) {
    if (op == null || fase.equals("reconciliar")) return;
    if (!transferencia) operaciones.cerrarAntesDeTransferir(op);
    else if (!fase.equals("publicar")) operaciones.incierta(op);
    else if (permanente(ex)) {
      if (operaciones.cerrarConfirmada(op))
        DiagnosticoCarga.reconciliacion(op.id(), "CONFLICTO_" + ((ExcepcionNegocio) ex).getEstado().value(), "CERRADA");
    } else operaciones.liberar(op);
  }

  /** Sin acceso (403/404) o en conflicto (409): repetir la misma clave nunca podría publicarse. */
  private static boolean permanente(Exception ex) {
    return ex instanceof ExcepcionNegocio e
        && java.util.Set.of(403, 404, 409).contains(e.getEstado().value());
  }
}
