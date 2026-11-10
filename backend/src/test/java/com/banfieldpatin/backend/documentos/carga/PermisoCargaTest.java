package com.banfieldpatin.backend.documentos.carga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.TipoDocumento;
import com.banfieldpatin.backend.documentos.almacen.AlmacenDocumentos;
import com.banfieldpatin.backend.documentos.almacen.DestinoPreparado;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/**
 * El permiso de procesamiento se toma en el servicio, con el archivo ya recibido, una sola vez, y se
 * libera exactamente una vez por cualquier salida. Sin capacidad se rechaza de inmediato, sin
 * encolar ni crear temporales.
 */
class PermisoCargaTest {
  @TempDir Path temporal;

  private final OperacionesCargaJdbc operaciones = mock(OperacionesCargaJdbc.class);
  private final AlmacenDocumentos almacen = mock(AlmacenDocumentos.class);

  private CargaDocumentalService servicio(int concurrencia) {
    when(almacen.prepararDestino())
        .thenReturn(DestinoPreparado.desdeRegistro("bucket-sintetico", UUID.randomUUID()));
    return new CargaDocumentalService(
        operaciones,
        new CargaPropiedades(
            true,
            5_000_000,
            temporal,
            16_000_000,
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            "127.0.0.1",
            3310,
            concurrencia,
            Duration.ofMinutes(3)),
        almacen);
  }

  private static int libres(CargaDocumentalService s) throws Exception {
    var campo = CargaDocumentalService.class.getDeclaredField("cupos");
    campo.setAccessible(true);
    return ((Semaphore) campo.get(s)).availablePermits();
  }

  private static MockMultipartFile png() throws Exception {
    return new MockMultipartFile(
        "archivo", "sintetico.png", "image/png", PreparadorDocumentoTest.imagen("png", 2, 2));
  }

  private static CargaDocumentalService.Resultado cargar(
      CargaDocumentalService s, org.springframework.web.multipart.MultipartFile archivo) {
    return s.cargar(
        null, UUID.randomUUID(), TipoDocumento.OTRO, null, UUID.randomUUID(), archivo, null);
  }

  private long directoriosDeCarga() throws IOException {
    try (var s = Files.list(temporal)) {
      return s.filter(p -> p.getFileName().toString().startsWith("c-")).count();
    }
  }

  @Test
  void sinCapacidadSeRechazaDeInmediatoSinEncolarNiCrearTemporales() throws Exception {
    var s = servicio(1);
    var dentro = new CountDownLatch(1);
    var liberar = new CountDownLatch(1);
    when(operaciones.reservar(any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              dentro.countDown();
              liberar.await(10, TimeUnit.SECONDS);
              throw ErrorCarga.conflicto();
            });
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var primera = pool.submit(() -> cargar(s, png()));
      assertThat(dentro.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(libres(s)).isZero();
      long antes = directoriosDeCarga();

      assertThatThrownBy(() -> cargar(s, png()))
          .isInstanceOfSatisfying(
              ExcepcionNegocio.class,
              e -> {
                assertThat(e.getEstado().value()).isEqualTo(429);
                assertThat(e.getCodigo()).isEqualTo("CARGA_OCUPADA");
              });
      assertThat(directoriosDeCarga()).as("el rechazo no prepara otro temporal").isEqualTo(antes);

      liberar.countDown();
      assertThatThrownBy(() -> primera.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ExcepcionNegocio.class);
    } finally {
      liberar.countDown();
    }
    assertThat(libres(s)).isEqualTo(1);
    assertThat(directoriosDeCarga()).isZero();
  }

  @Test
  void elPermisoSeLiberaAnteRechazoExcepcionYAbortoDelCliente() throws Exception {
    var s = servicio(2);

    // Rechazo del documento (422).
    var invalido = new MockMultipartFile("archivo", "sintetico.png", "image/png", "no".getBytes());
    assertThatThrownBy(() -> cargar(s, invalido)).isInstanceOf(ExcepcionNegocio.class);
    assertThat(libres(s)).isEqualTo(2);

    // Excepción no controlada de la base al reservar.
    when(operaciones.reservar(any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("sintetico"));
    assertThatThrownBy(() -> cargar(s, png())).isInstanceOf(ExcepcionNegocio.class);
    assertThat(libres(s)).isEqualTo(2);

    // El cliente cancela: la lectura del archivo falla a mitad de camino.
    var abortado =
        new MockMultipartFile("archivo", "sintetico.png", "image/png", new byte[] {1}) {
          @Override
          public InputStream getInputStream() throws IOException {
            throw new IOException("conexión cerrada por el cliente");
          }
        };
    assertThatThrownBy(() -> cargar(s, abortado)).isInstanceOf(ExcepcionNegocio.class);
    assertThat(libres(s)).isEqualTo(2);
    assertThat(directoriosDeCarga()).isZero();
  }

  @Test
  void elPermisoSeLiberaTrasUnaCargaExitosaRepetida() throws Exception {
    var s = servicio(1);
    var publicada =
        new com.banfieldpatin.backend.documentos.DocumentoMetadatos(
            UUID.randomUUID(),
            TipoDocumento.OTRO,
            com.banfieldpatin.backend.documentos.EstadoDocumento.CARGADO,
            null,
            java.time.Instant.now(),
            true,
            null,
            null);
    when(operaciones.reservar(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new OperacionesCargaJdbc.Reserva(null, publicada));
    for (int i = 0; i < 3; i++) assertThat(cargar(s, png()).repetida()).isTrue();
    assertThat(libres(s)).isEqualTo(1);
  }
}
