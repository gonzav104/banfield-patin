package com.banfieldpatin.backend.documentos.carga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * La inspección distingue tres resultados: un documento inválido (exit 42) es 422; el vencimiento
 * del plazo de la JVM hija es un fallo temporal de infraestructura (503); y un documento válido
 * pasa. En el timeout la JVM hija se termina y el temporal se elimina.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClasificacionInspeccionTest {
  @TempDir Path temporal;

  private static MockMultipartFile png(byte[] contenido) {
    return new MockMultipartFile("archivo", "sintetico.png", "image/png", contenido);
  }

  private CargaPropiedades propiedades(Duration procesamiento) {
    return new CargaPropiedades(
        true,
        5_000_000,
        temporal,
        16_000_000,
        procesamiento,
        Duration.ofSeconds(1),
        "127.0.0.1",
        3310,
        2,
        Duration.ofMinutes(3));
  }

  private static List<Long> hijosVivos() {
    return ProcessHandle.current().children().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
  }

  private void sinResiduos() throws Exception {
    try (var s = Files.list(temporal)) {
      assertThat(s.toList()).isEmpty();
    }
  }

  @Test
  void elVencimientoDelPlazoEs503NoDisponibleYNoDejaProcesosNiTemporales(CapturedOutput salida)
      throws Exception {
    var antes = hijosVivos();
    var archivo = png(PreparadorDocumentoTest.imagen("png", 2, 2));

    assertThatThrownBy(
            () -> new PreparadorDocumento(propiedades(Duration.ofMillis(1))).preparar(archivo))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class,
            e -> {
              assertThat(e.getEstado().value()).isEqualTo(503);
              assertThat(e.getCodigo()).isEqualTo("CARGA_NO_DISPONIBLE");
              assertThat(e.getCause()).isNull();
            });

    assertThat(hijosVivos()).allMatch(antes::contains);
    sinResiduos();
    // Observabilidad de U2: fase y causa saneadas, sin rutas ni nombres.
    assertThat(salida.getAll())
        .contains("Fallo en la carga documental")
        .contains("fase=inspeccionar")
        .contains("causa=TimeoutException")
        .doesNotContain(temporal.toString())
        .doesNotContain("sintetico.png")
        .doesNotContain("contenido");
  }

  @Test
  void unDocumentoInvalidoSigueSiendo422AunqueElPlazoSeaAmplio(CapturedOutput salida)
      throws Exception {
    var antes = hijosVivos();
    var invalido = png("no es un png".getBytes());

    assertThatThrownBy(
            () -> new PreparadorDocumento(propiedades(Duration.ofSeconds(30))).preparar(invalido))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class,
            e -> {
              assertThat(e.getEstado().value()).isEqualTo(422);
              assertThat(e.getCodigo()).isEqualTo("ARCHIVO_INVALIDO");
            });

    assertThat(hijosVivos()).allMatch(antes::contains);
    sinResiduos();
    assertThat(salida.getAll()).doesNotContain("fase=inspeccionar");
  }

  @Test
  void unDocumentoValidoPasaSinAdvertencias(CapturedOutput salida) throws Exception {
    var valido = png(PreparadorDocumentoTest.imagen("png", 2, 2));

    assertThatCode(
            () -> {
              try (var preparado =
                  new PreparadorDocumento(propiedades(Duration.ofSeconds(30))).preparar(valido)) {
                assertThat(preparado.mime).isEqualTo("image/png");
              }
            })
        .doesNotThrowAnyException();

    sinResiduos();
    assertThat(salida.getAll()).doesNotContain("Fallo en la carga documental");
  }
}
