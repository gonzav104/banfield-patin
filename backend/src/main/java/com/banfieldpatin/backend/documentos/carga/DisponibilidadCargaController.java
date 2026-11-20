package com.banfieldpatin.backend.documentos.carga;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Indica a la familia si la carga documental está habilitada por configuración y cuál es el límite
 * de bytes por archivo. Usa la misma condición que {@link CargaDocumentalController}: la presencia
 * del servicio de carga. No consulta la salud del escáner ni de Storage, por lo que una carga puede
 * seguir respondiendo 503 aunque esté habilitada.
 */
@RestController
public class DisponibilidadCargaController {
  private final ObjectProvider<CargaDocumentalService> servicios;
  private final ObjectProvider<CargaPropiedades> propiedades;

  public DisponibilidadCargaController(
      ObjectProvider<CargaDocumentalService> servicios,
      ObjectProvider<CargaPropiedades> propiedades) {
    this.servicios = servicios;
    this.propiedades = propiedades;
  }

  @GetMapping("/api/familia/documentos/disponibilidad")
  public DisponibilidadCarga disponibilidad() {
    var p = propiedades.getIfAvailable();
    if (servicios.getIfAvailable() == null || p == null) return new DisponibilidadCarga(false, null);
    return new DisponibilidadCarga(true, p.maxBytes());
  }

  /** {@code maxBytes} es null cuando la carga no está habilitada. */
  public record DisponibilidadCarga(boolean habilitada, Long maxBytes) {}
}
