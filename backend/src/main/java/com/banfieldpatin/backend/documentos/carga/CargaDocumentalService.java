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

  boolean entrar() {
    return cupos.tryAcquire();
  }

  void salir() {
    cupos.release();
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

  Resultado cargar(
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
    try {
      if (TransactionSynchronizationManager.isActualTransactionActive())
        throw ErrorCarga.indisponible();
      try (var a = preparador.preparar(archivo)) {
        fase = "reservar";
        var reserva =
            operaciones.reservar(c, atleta, tipo, temporada, idem, almacen.prepararDestino(), a);
        if (reserva.publicada() != null) return new Resultado(reserva.publicada(), true);
        op = reserva.operacion();
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
      // Un 409 con la operación ya reservada es una pérdida de CAS: anómalo, a diferencia de un 4xx del cliente.
      if (ex.getEstado().is5xxServerError() || op != null && ex.getEstado().value() == 409)
        DiagnosticoCarga.fallo(op == null ? null : op.id(), fase, ex);
      if (op != null) {
        if (transferencia) operaciones.incierta(op);
        else operaciones.cerrarAntesDeTransferir(op);
      }
      throw ex;
    } catch (Exception ex) {
      DiagnosticoCarga.fallo(op == null ? null : op.id(), fase, ex);
      if (op != null) {
        if (transferencia) operaciones.incierta(op);
        else operaciones.cerrarAntesDeTransferir(op);
      }
      throw transferencia ? ErrorCarga.incierto() : ErrorCarga.indisponible();
    }
  }
}
