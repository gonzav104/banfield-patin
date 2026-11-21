package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import org.springframework.http.HttpStatus;

final class ErrorCarga {
  private ErrorCarga() {}

  static ExcepcionNegocio invalido() {
    return new ExcepcionNegocio(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "ARCHIVO_INVALIDO",
        "El archivo no es válido o no está permitido.");
  }

  static ExcepcionNegocio indisponible() {
    return new ExcepcionNegocio(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CARGA_NO_DISPONIBLE",
        "La carga documental no está disponible.");
  }

  /** Predecesor inexistente, ajeno, de otro deportista o de un tipo que el reemplazo no admite: siempre el mismo 404. */
  static ExcepcionNegocio documentoNoEncontrado() {
    return new ExcepcionNegocio(
        HttpStatus.NOT_FOUND, "DOCUMENTO_NO_ENCONTRADO", "Documento no encontrado.");
  }

  static ExcepcionNegocio conflicto() {
    return new ExcepcionNegocio(
        HttpStatus.CONFLICT,
        "OPERACION_NO_REPETIBLE",
        "La operación está en curso, requiere resolución o no coincide con la solicitud.");
  }

  /**
   * Otra solicitud mantiene viva la reserva de esta clave: transitorio. Repetir la MISMA clave y
   * los MISMOS bytes puede retomar o devolver la operación cuando la reserva venza.
   */
  static ExcepcionNegocio enCurso() {
    return new ExcepcionNegocio(
        HttpStatus.CONFLICT,
        "OPERACION_EN_CURSO",
        "La operación anterior todavía se está procesando; reintente con la misma clave.");
  }

  static ExcepcionNegocio incierto() {
    return new ExcepcionNegocio(
        HttpStatus.SERVICE_UNAVAILABLE,
        "RESULTADO_CARGA_INCIERTO",
        "No se puede confirmar el resultado; conserve la clave de idempotencia.");
  }

  static ExcepcionNegocio ocupada() {
    return new ExcepcionNegocio(
        HttpStatus.TOO_MANY_REQUESTS, "CARGA_OCUPADA", "No hay capacidad de carga disponible.");
  }

  static ExcepcionNegocio grande() {
    return new ExcepcionNegocio(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "ARCHIVO_DEMASIADO_GRANDE",
        "El archivo supera el límite permitido.");
  }
}
