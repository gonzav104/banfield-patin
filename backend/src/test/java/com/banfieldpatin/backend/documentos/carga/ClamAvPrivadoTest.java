package com.banfieldpatin.backend.documentos.carga;

import static org.assertj.core.api.Assertions.*;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClamAvPrivadoTest {
  @TempDir Path temporal;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "stream: OK\0",
        "stream: Eicar-Sintetico FOUND\0",
        "stream: ERROR\0",
        "stream: OK\n",
        "",
        "stream: OK\0otra respuesta\0"
      })
  void exigeConfirmacionLimpiaUnicaYProtocoloAcotado(String respuesta) throws Exception {
    byte[] data = "contenido completamente sintetico".getBytes();
    Path archivo = temporal.resolve("archivo");
    Files.write(archivo, data);
    try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      var captured = new CompletableFuture<byte[]>();
      Thread t =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (var socket = server.accept();
                        var in = new DataInputStream(socket.getInputStream())) {
                      assertThat(in.readNBytes(10)).isEqualTo("zINSTREAM\0".getBytes());
                      var total = new ByteArrayOutputStream();
                      int n;
                      while ((n = in.readInt()) != 0) {
                        assertThat(n).isBetween(1, 8192);
                        total.write(in.readNBytes(n));
                      }
                      captured.complete(total.toByteArray());
                      socket.getOutputStream().write(respuesta.getBytes());
                    } catch (Throwable ex) {
                      captured.completeExceptionally(ex);
                    }
                  });
      var p =
          new CargaPropiedades(
              true,
              15000000,
              temporal,
              12000000,
              Duration.ofSeconds(10),
              Duration.ofMillis(400),
              "127.0.0.1",
              server.getLocalPort(),
              2,
              Duration.ofMinutes(3));
      var scanner = new ClamAvPrivado(p);
      if (respuesta.equals("stream: OK\0")) {
        scanner.escanear(archivo);
        assertThat(captured.get(2, TimeUnit.SECONDS)).isEqualTo(data);
      } else
        assertThatThrownBy(() -> scanner.escanear(archivo))
            .isInstanceOf(ExcepcionNegocio.class)
            .hasMessageNotContaining("Eicar")
            .hasMessageNotContaining("127.0.0.1");
      server.close();
      t.join(1000);
    }
  }

  @Test
  void indisponibilidadFallaCerrado() throws Exception {
    Path file = temporal.resolve("archivo");
    Files.writeString(file, "sintetico");
    int puerto;
    try (var server = new ServerSocket(0)) {
      puerto = server.getLocalPort();
    }
    var p =
        new CargaPropiedades(
            true,
            100,
            temporal,
            100,
            Duration.ofSeconds(1),
            Duration.ofMillis(100),
            "127.0.0.1",
            puerto,
            1,
            Duration.ofMinutes(1));
    assertThatThrownBy(() -> new ClamAvPrivado(p).escanear(file))
        .isInstanceOf(ExcepcionNegocio.class);
  }
}
