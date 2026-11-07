package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;

/**
 * INSTREAM privado; sin rutas remotas, reintentos ni logs del proveedor. Plazo total también para
 * escrituras.
 */
final class ClamAvPrivado {
  private final CargaPropiedades p;
  private final InetSocketAddress destino;

  ClamAvPrivado(CargaPropiedades p) {
    this.p = p;
    try {
      if (!(p.scannerHost().matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
          || p.scannerHost().contains(":") && p.scannerHost().matches("[0-9a-fA-F:]+")))
        throw new IOException();
      var ip = InetAddress.getByName(p.scannerHost());
      byte[] raw = ip.getAddress();
      boolean ula = raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
      if (!(ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ula)
          || ip.isAnyLocalAddress()
          || ip.isMulticastAddress()) throw new IOException();
      destino = new InetSocketAddress(ip, p.scannerPuerto());
    } catch (Exception ex) {
      throw new IllegalStateException("El scanner requiere una dirección IP privada explícita");
    }
  }

  void escanear(Path archivo) {
    long fin = System.nanoTime() + p.scannerPlazo().toNanos();
    try (var socket = SocketChannel.open();
        var selector = Selector.open();
        var in = Files.newInputStream(archivo)) {
      socket.configureBlocking(false);
      var key = socket.register(selector, SelectionKey.OP_CONNECT);
      socket.connect(destino);
      while (!socket.finishConnect()) esperar(selector, key, SelectionKey.OP_CONNECT, fin);
      escribir(
          socket,
          selector,
          key,
          ByteBuffer.wrap("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII)),
          fin);
      byte[] buffer = new byte[8192];
      int n;
      long total = 0;
      while ((n = in.read(buffer)) != -1) {
        total += n;
        if (total > p.maxBytes()) throw ErrorCarga.grande();
        escribir(socket, selector, key, ByteBuffer.allocate(4).putInt(n).flip(), fin);
        escribir(socket, selector, key, ByteBuffer.wrap(buffer, 0, n), fin);
      }
      escribir(socket, selector, key, ByteBuffer.allocate(4).putInt(0).flip(), fin);
      var respuesta = new ByteArrayOutputStream();
      var buf = ByteBuffer.allocate(1024);
      while (true) {
        verificar(fin);
        n = socket.read(buf);
        if (n < 0) break;
        if (n == 0) {
          esperar(selector, key, SelectionKey.OP_READ, fin);
          continue;
        }
        if (respuesta.size() + n > 4096) throw ErrorCarga.indisponible();
        respuesta.write(buf.array(), 0, n);
        buf.clear();
      }
      String resultado = respuesta.toString(StandardCharsets.US_ASCII);
      if (resultado.equals("stream: OK\0")) return;
      if (resultado.matches("stream: [^\\x00\\r\\n]{1,300} FOUND\\x00"))
        throw new ExcepcionNegocio(
            HttpStatus.UNPROCESSABLE_ENTITY, "ARCHIVO_PELIGROSO", "El scanner rechazó el archivo.");
      throw ErrorCarga.indisponible();
    } catch (ExcepcionNegocio ex) {
      throw ex;
    } catch (Exception ex) {
      throw ErrorCarga.indisponible();
    }
  }

  private static void verificar(long fin) throws IOException {
    if (Thread.currentThread().isInterrupted() || System.nanoTime() >= fin)
      throw new IOException("Plazo agotado");
  }

  private static void esperar(Selector s, SelectionKey k, int op, long fin) throws IOException {
    verificar(fin);
    k.interestOps(op);
    s.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(fin - System.nanoTime())));
    s.selectedKeys().clear();
    verificar(fin);
  }

  private static void escribir(SocketChannel c, Selector s, SelectionKey k, ByteBuffer b, long fin)
      throws IOException {
    while (b.hasRemaining()) {
      verificar(fin);
      if (c.write(b) == 0) esperar(s, k, SelectionKey.OP_WRITE, fin);
    }
  }
}
