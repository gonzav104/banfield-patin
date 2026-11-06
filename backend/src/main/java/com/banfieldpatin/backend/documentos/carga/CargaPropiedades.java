package com.banfieldpatin.backend.documentos.carga;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("banfield.documentos.carga")
public record CargaPropiedades(
    @DefaultValue("false") boolean habilitada,
    @DefaultValue("15000000") long maxBytes,
    Path temporales,
    @DefaultValue("12000000") long maxPixeles,
    @DefaultValue("PT10S") Duration procesamiento,
    @DefaultValue("PT20S") Duration scannerPlazo,
    @DefaultValue("127.0.0.1") String scannerHost,
    @DefaultValue("3310") int scannerPuerto,
    @DefaultValue("2") int concurrencia,
    @DefaultValue("PT3M") Duration reserva) {
  public CargaPropiedades {
    if (maxBytes < 1
        || maxBytes > 50000000
        || maxPixeles < 1
        || maxPixeles > 16000000
        || !plazo(procesamiento)
        || !plazo(scannerPlazo)
        || !plazo(reserva)
        || scannerHost == null
        || scannerPuerto < 1
        || scannerPuerto > 65535
        || concurrencia < 1
        || concurrencia > 8)
      throw new IllegalStateException("Configuración de carga documental inválida");
    temporales =
        (temporales == null
                ? Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "banfield-cargas-" + ProcessHandle.current().pid())
                : temporales)
            .toAbsolutePath()
            .normalize();
  }

  private static boolean plazo(Duration d) {
    return d != null && d.toMillis() >= 1 && d.compareTo(Duration.ofMinutes(10)) <= 0;
  }

  @Override
  public String toString() {
    return "CargaPropiedades[privadas]";
  }
}
