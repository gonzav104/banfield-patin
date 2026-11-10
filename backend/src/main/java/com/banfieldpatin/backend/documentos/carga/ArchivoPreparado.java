package com.banfieldpatin.backend.documentos.carga;

import java.io.IOException;
import java.nio.file.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Temporal privado, nunca es DTO ni conserva el nombre original. */
final class ArchivoPreparado implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(ArchivoPreparado.class);

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

  /** La limpieza es de mejor esfuerzo: nunca altera el resultado de la petición. */
  @Override
  public void close() {
    eliminar(ruta.getParent());
  }

  /**
   * Elimina el directorio privado de una carga y su contenido sin lanzar excepciones: un fallo aquí
   * no puede convertir una publicación confirmada en error ni reemplazar el error original. Ante
   * un fallo el temporal permanece con sus permisos privados (0700/0600) y se registra una única
   * advertencia con la clase de la causa, sin rutas, nombres ni mensajes del sistema.
   */
  static void eliminar(Path dir) {
    if (dir == null) return;
    Class<?> causa = null;
    try (var archivos = Files.list(dir)) {
      for (Path p : archivos.toList())
        try {
          Files.deleteIfExists(p);
        } catch (IOException | RuntimeException ex) {
          causa = ex.getClass();
        }
    } catch (NoSuchFileException ex) {
      return;
    } catch (IOException | RuntimeException ex) {
      causa = ex.getClass();
    }
    try {
      Files.deleteIfExists(dir);
    } catch (IOException | RuntimeException ex) {
      causa = ex.getClass();
    }
    if (causa != null)
      log.warn(
          "No se pudo eliminar un temporal de carga; permanece privado hasta su limpieza posterior"
              + " (causa: {})",
          causa.getSimpleName());
  }

  @Override
  public String toString() {
    return "ArchivoPreparado[privado]";
  }
}
