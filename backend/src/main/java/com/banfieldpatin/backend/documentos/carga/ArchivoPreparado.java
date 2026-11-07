package com.banfieldpatin.backend.documentos.carga;

import java.io.IOException;
import java.nio.file.*;

/** Temporal privado, nunca es DTO ni conserva el nombre original. */
final class ArchivoPreparado implements AutoCloseable {
  final Path ruta;
  final String mime;
  final long bytes;
  final byte[] sha;

  ArchivoPreparado(Path ruta, String mime, long bytes, byte[] sha) {
    this.ruta = ruta;
    this.mime = mime;
    this.bytes = bytes;
    this.sha = sha.clone();
  }

  @Override
  public void close() {
    try {
      Files.deleteIfExists(ruta);
      Files.deleteIfExists(ruta.getParent());
    } catch (IOException ex) {
      throw ErrorCarga.indisponible();
    }
  }

  @Override
  public String toString() {
    return "ArchivoPreparado[privado]";
  }
}
