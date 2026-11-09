package com.banfieldpatin.backend.documentos.carga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.DocumentoMetadatos;
import com.banfieldpatin.backend.documentos.EstadoDocumento;
import com.banfieldpatin.backend.documentos.TipoDocumento;
import com.banfieldpatin.backend.documentos.almacen.AlmacenDocumentos;
import com.banfieldpatin.backend.documentos.almacen.DestinoPreparado;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Un fallo al eliminar el temporal no cambia el resultado de la petición: ni convierte una
 * publicación confirmada en 503 ni reemplaza el error original, y no filtra rutas ni nombres.
 */
@ExtendWith(OutputCaptureExtension.class)
class LimpiezaTemporalesCargaTest {
  @TempDir Path temporal;

  @BeforeEach
  void soloConPermisosPosixEfectivos() throws Exception {
    // Como superusuario el sistema operativo permitiría borrar igualmente: la prueba no aplica.
    assumeThat(System.getProperty("user.name")).isNotEqualTo("root");
  }

  @AfterEach
  void restaurarPermisos() throws Exception {
    try (var s = Files.walk(temporal)) {
      for (Path p : s.filter(Files::isDirectory).toList())
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
    }
  }

  private static void bloquearBorrado(Path dir) throws Exception {
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
  }

  private Path unicoDirectorioDeCarga() throws Exception {
    try (var s = Files.list(temporal)) {
      var dirs = s.filter(p -> p.getFileName().toString().startsWith("c-")).toList();
      assertThat(dirs).hasSize(1);
      return dirs.get(0);
    }
  }

  private static MockMultipartFile png(byte[] contenido) {
    return new MockMultipartFile("archivo", "sintetico.png", "image/png", contenido);
  }

  @Test
  void cierreExitosoEliminaArchivoYDirectorio() throws Exception {
    var preparado =
        new PreparadorDocumento(PreparadorDocumentoTest.propiedades(temporal, 5_000_000))
            .preparar(png(PreparadorDocumentoTest.imagen("png", 2, 2)));
    Path dir = preparado.ruta.getParent();
    assertThat(dir).isDirectory();
    preparado.close();
    assertThat(dir).doesNotExist();
  }

  @Test
  void cierreConFalloNoLanzaConservaElTemporalPrivadoYNoExponeRutas(CapturedOutput salida)
      throws Exception {
    var preparado =
        new PreparadorDocumento(PreparadorDocumentoTest.propiedades(temporal, 5_000_000))
            .preparar(png(PreparadorDocumentoTest.imagen("png", 2, 2)));
    Path dir = preparado.ruta.getParent();
    bloquearBorrado(dir);
    preparado.close();
    assertThat(preparado.ruta).exists();
    assertThat(Files.getPosixFilePermissions(preparado.ruta))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertThat(salida.getAll())
        .contains("temporal de carga")
        .doesNotContain(temporal.toString())
        .doesNotContain(dir.getFileName().toString())
        .doesNotContain("contenido")
        .doesNotContain("sintetico.png");
  }

  @Test
  void rechazoDelArchivoConservaElErrorOriginalAunqueFalleLaLimpieza() throws Exception {
    var invalido =
        new MockMultipartFile("archivo", "sintetico.png", "image/png", "no es un png".getBytes()) {
          @Override
          public java.io.InputStream getInputStream() throws java.io.IOException {
            try {
              bloquearBorrado(unicoDirectorioDeCarga());
            } catch (Exception ex) {
              throw new java.io.IOException(ex);
            }
            return super.getInputStream();
          }
        };
    assertThatThrownBy(
            () ->
                new PreparadorDocumento(PreparadorDocumentoTest.propiedades(temporal, 5_000_000))
                    .preparar(invalido))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class,
            e -> {
              assertThat(e.getEstado().value()).isEqualTo(422);
              assertThat(e.getCodigo()).isEqualTo("ARCHIVO_INVALIDO");
            });
  }

  @Test
  void cargaPublicadaResponde201AunqueFalleLaLimpiezaDelTemporal() throws Exception {
    var contenido = PreparadorDocumentoTest.imagen("png", 2, 2);
    try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      Thread.ofVirtual()
          .start(
              () -> {
                try (var socket = server.accept();
                    var in = new DataInputStream(socket.getInputStream())) {
                  in.readNBytes(10);
                  var total = new ByteArrayOutputStream();
                  int n;
                  while ((n = in.readInt()) != 0) total.write(in.readNBytes(n));
                  socket.getOutputStream().write("stream: OK\0".getBytes());
                } catch (Exception ignorada) {
                  /* el scanner sintético sólo existe para esta prueba */
                }
              });
      var p =
          new CargaPropiedades(
              true,
              5_000_000,
              temporal,
              16_000_000,
              Duration.ofSeconds(10),
              Duration.ofSeconds(5),
              "127.0.0.1",
              server.getLocalPort(),
              2,
              Duration.ofMinutes(3));
      var destino = DestinoPreparado.desdeRegistro("bucket-sintetico", UUID.randomUUID());
      var op =
          new OperacionesCargaJdbc.Operacion(
              UUID.randomUUID(),
              0,
              "RESERVADA",
              UUID.randomUUID(),
              "OTRO",
              null,
              destino,
              new byte[32],
              "image/png",
              contenido.length);
      var documento =
          new DocumentoMetadatos(
              op.id(),
              TipoDocumento.OTRO,
              EstadoDocumento.CARGADO,
              null,
              Instant.now(),
              true,
              null,
              null);
      var operaciones = mock(OperacionesCargaJdbc.class);
      var almacen = mock(AlmacenDocumentos.class);
      when(almacen.prepararDestino()).thenReturn(destino);
      when(operaciones.reservar(any(), any(), any(), any(), any(), any(), any()))
          .thenReturn(new OperacionesCargaJdbc.Reserva(op, null));
      when(operaciones.iniciar(any(), any())).thenReturn(op);
      when(operaciones.almacenada(any())).thenReturn(op);
      when(operaciones.publicar(any(), any(), any())).thenReturn(documento);
      doAnswer(
              inv -> {
                bloquearBorrado(unicoDirectorioDeCarga());
                return null;
              })
          .when(almacen)
          .crear(any(), any(), anyLong(), anyString());

      var resultado =
          new CargaDocumentalService(operaciones, p, almacen)
              .cargar(
                  null,
                  op.atleta(),
                  TipoDocumento.OTRO,
                  null,
                  UUID.randomUUID(),
                  png(contenido),
                  null);

      assertThat(resultado.documento()).isEqualTo(documento);
      assertThat(resultado.repetida()).isFalse();
      verify(operaciones, never()).incierta(any());
      verify(operaciones, never()).cerrarAntesDeTransferir(any());
    }
  }
}
