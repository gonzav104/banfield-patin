package com.banfieldpatin.backend.documentos.carga;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** La reserva debe superar el escaneo y una operación completa de Storage. */
class ReservaPlazosTest {
  @TempDir Path temporal;

  private CargaPropiedades propiedades(Duration scanner, Duration reserva) {
    return new CargaPropiedades(
        true, 5_000_000, temporal, 16_000_000, Duration.ofSeconds(10), scanner, "127.0.0.1", 3310, 2,
        reserva);
  }

  @Test
  void valoresPorDefectoSonValidos() {
    // 3 min frente a 20 s de escaneo y 2 × (3 s + 15 s) de Storage.
    assertThatCode(
            () ->
                CargaConfiguracion.validarReserva(
                    propiedades(Duration.ofSeconds(20), Duration.ofMinutes(3)), Duration.ofSeconds(36)))
        .doesNotThrowAnyException();
  }

  @Test
  void reservaQueNoCubreElEscaneoSeRechaza() {
    assertThatThrownBy(
            () ->
                CargaConfiguracion.validarReserva(
                    propiedades(Duration.ofSeconds(20), Duration.ofSeconds(20)), null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void reservaQueNoCubreUnaOperacionDeStorageSeRechaza() {
    assertThatThrownBy(
            () ->
                CargaConfiguracion.validarReserva(
                    propiedades(Duration.ofSeconds(5), Duration.ofSeconds(30)), Duration.ofSeconds(36)))
        .isInstanceOf(IllegalStateException.class);
    assertThatCode(
            () ->
                CargaConfiguracion.validarReserva(
                    propiedades(Duration.ofSeconds(5), Duration.ofSeconds(37)), Duration.ofSeconds(36)))
        .doesNotThrowAnyException();
  }
}
