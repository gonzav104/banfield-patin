package com.banfieldpatin.backend.documentos.carga;

import static org.assertj.core.api.Assertions.*;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockMultipartFile;

class RecursosCargaTest {
  @TempDir Path temporal;

  @Test
  void integracionDesactivadaNoCreaInfraestructuraNiTemporales() {
    Path dir = temporal.resolve("no-creado");
    new ApplicationContextRunner()
        .withUserConfiguration(CargaConfiguracion.class)
        .withPropertyValues("banfield.documentos.carga.temporales=" + dir)
        .run(
            c -> {
              assertThat(c).doesNotHaveBean(CargaDocumentalService.class);
              assertThat(c).doesNotHaveBean(jakarta.servlet.MultipartConfigElement.class);
              assertThat(dir).doesNotExist();
            });
  }

  @Test
  void cuentaBytesRealesAunqueElMetadatoMienta() throws Exception {
    var file =
        new MockMultipartFile(
            "archivo", "sintetico.png", "image/png", PreparadorDocumentoTest.imagen("png", 2, 2)) {
          @Override
          public long getSize() {
            return 1;
          }
        };
    assertThatThrownBy(
            () ->
                new PreparadorDocumento(PreparadorDocumentoTest.propiedades(temporal, 5))
                    .preparar(file))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class, e -> assertThat(e.getEstado().value()).isEqualTo(413));
    try (var s = Files.list(temporal)) {
      assertThat(s.count()).isZero();
    }
  }

  @Test
  void directorioNoPrivadoFallaComoIndisponibilidadSinModificarlo() throws Exception {
    Files.setPosixFilePermissions(temporal, PosixFilePermissions.fromString("rwxr-xr-x"));
    var f =
        new MockMultipartFile(
            "archivo", "sintetico.png", "image/png", PreparadorDocumentoTest.imagen("png", 2, 2));
    assertThatThrownBy(
            () ->
                new PreparadorDocumento(PreparadorDocumentoTest.propiedades(temporal, 15000000))
                    .preparar(f))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class, e -> assertThat(e.getEstado().value()).isEqualTo(503));
    assertThat(Files.getPosixFilePermissions(temporal))
        .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  @Test
  void plazoDeProcesoNoDejaUnaJVMHijaViva() throws Exception {
    var before = ProcessHandle.current().children().map(ProcessHandle::pid).toList();
    var p =
        new CargaPropiedades(
            true,
            15000000,
            temporal,
            12000000,
            Duration.ofMillis(1),
            Duration.ofSeconds(1),
            "127.0.0.1",
            3310,
            1,
            Duration.ofMinutes(1));
    var file =
        new MockMultipartFile(
            "archivo", "sintetico.png", "image/png", PreparadorDocumentoTest.imagen("png", 2, 2));
    assertThatThrownBy(() -> new PreparadorDocumento(p).preparar(file))
        .isInstanceOf(ExcepcionNegocio.class);
    assertThat(
            ProcessHandle.current()
                .children()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .toList())
        .allMatch(before::contains);
    try (var s = Files.list(temporal)) {
      assertThat(s.count()).isZero();
    }
  }

  @Test
  void scannerNoAdmiteDestinosPublicosNiNombresDNS() {
    for (String host : new String[] {"8.8.8.8", "0.0.0.0", "224.0.0.1"}) {
      var p =
          new CargaPropiedades(
              true,
              10,
              temporal,
              10,
              Duration.ofSeconds(1),
              Duration.ofSeconds(1),
              host,
              3310,
              1,
              Duration.ofMinutes(1));
      assertThatThrownBy(() -> new ClamAvPrivado(p)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void rechazaNombresAntesDeCualquierResolucionDNS() {
    try (var dns = org.mockito.Mockito.mockStatic(java.net.InetAddress.class)) {
      for (String host : new String[] {"a.de", "abcdef"}) {
        var p =
            new CargaPropiedades(
                true,
                10,
                temporal,
                10,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                host,
                3310,
                1,
                Duration.ofMinutes(1));
        assertThatThrownBy(() -> new ClamAvPrivado(p)).isInstanceOf(IllegalStateException.class);
      }
      dns.verifyNoInteractions();
    }
  }
}
