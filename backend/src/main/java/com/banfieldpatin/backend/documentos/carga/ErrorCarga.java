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

  static ExcepcionNegocio conflicto() {
    return new ExcepcionNegocio(
        HttpStatus.CONFLICT,
        "OPERACION_NO_REPETIBLE",
        "La operación está en curso, requiere resolución o no coincide con la solicitud.");
  }

  static ExcepcionNegocio incierto() {
    return new ExcepcionNegocio(
        HttpStatus.SERVICE_UNAVAILABLE,
        "RESULTADO_CARGA_INCIERTO",
        "No se puede confirmar el resultado; conserve la clave de idempotencia.");
  }

  static ExcepcionNegocio grande() {
    return new ExcepcionNegocio(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "ARCHIVO_DEMASIADO_GRANDE",
        "El archivo supera el límite permitido.");
  }
}
