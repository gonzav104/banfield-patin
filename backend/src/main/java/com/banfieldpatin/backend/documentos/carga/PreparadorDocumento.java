package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.web.multipart.MultipartFile;

/**
 * No retiene el nombre original. El proceso hijo tiene heap y tiempo propios, sin secretos en su
 * entorno.
 */
final class PreparadorDocumento {
  private final CargaPropiedades p;

  PreparadorDocumento(CargaPropiedades p) {
    this.p = p;
  }

  static void directorioPrivado(Path dir) throws IOException {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS))
      Files.createDirectory(
          dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    if (Files.isSymbolicLink(dir)
        || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
        || !Files.getPosixFilePermissions(dir).equals(PosixFilePermissions.fromString("rwx------")))
      throw new IOException("Temporal no privado");
  }

  ArchivoPreparado preparar(MultipartFile archivo) {
    Path dir = null;
    try {
      String nombre = archivo.getOriginalFilename(), mime = archivo.getContentType();
      if (nombre == null
          || nombre.length() > 255
          || nombre.indexOf('/') >= 0
          || nombre.indexOf('\\') >= 0
          || nombre.chars().anyMatch(c -> c < 32)) throw ErrorCarga.invalido();
      String ext = nombre.substring(nombre.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
      String esperado =
          switch (ext) {
            case "pdf" -> "application/pdf";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            default -> "";
          };
      if (esperado.isEmpty() || !esperado.equals(mime)) throw ErrorCarga.invalido();
      if (archivo.getSize() > p.maxBytes()) throw ErrorCarga.grande();
      directorioPrivado(p.temporales());
      dir =
          Files.createTempDirectory(
              p.temporales(),
              "c-",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      Path file =
          Files.createFile(
              dir.resolve("contenido"),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      var digest = MessageDigest.getInstance("SHA-256");
      long total = 0;
      try (var in = archivo.getInputStream();
          var out = Files.newOutputStream(file)) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
          if (n == 0) {
            int b = in.read();
            if (b < 0) break;
            buf[0] = (byte) b;
            n = 1;
          }
          total += n;
          if (total > p.maxBytes()) throw ErrorCarga.grande();
          digest.update(buf, 0, n);
          out.write(buf, 0, n);
        }
      }
      if (total == 0) throw ErrorCarga.invalido();
      inspeccionar(file, mime, dir);
      return new ArchivoPreparado(file, mime, total, digest.digest());
    } catch (ExcepcionNegocio ex) {
      limpiar(dir);
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      limpiar(dir);
      throw ErrorCarga.indisponible();
    } catch (Exception ex) {
      limpiar(dir);
      throw ErrorCarga.indisponible();
    }
  }

  private void inspeccionar(Path file, String mime, Path dir)
      throws IOException, InterruptedException {
    String cp =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    var cmd =
        new ArrayList<String>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-Xss512k",
                "-XX:MaxMetaspaceSize=96m",
                "-Djava.awt.headless=true",
                "-Djava.io.tmpdir=" + dir));
    boolean jar = cp.endsWith(".jar") && !cp.contains(File.pathSeparator);
    if (jar) cmd.add("-Dloader.main=" + InspeccionContenido.class.getName());
    cmd.addAll(
        List.of(
            "-cp",
            cp,
            jar
                ? "org.springframework.boot.loader.launch.PropertiesLauncher"
                : InspeccionContenido.class.getName(),
            file.toString(),
            mime,
            Long.toString(p.maxPixeles())));
    ProcessBuilder pb =
        new ProcessBuilder(cmd)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    pb.environment().clear();
    pb.environment().put("LANG", "C");
    Process proceso = pb.start();
    try {
      if (!proceso.waitFor(p.procesamiento().toMillis(), TimeUnit.MILLISECONDS))
        throw ErrorCarga.invalido();
      if (proceso.exitValue() == 42) throw ErrorCarga.invalido();
      if (proceso.exitValue() != 0) throw ErrorCarga.indisponible();
    } finally {
      if (proceso.isAlive()) {
        proceso.destroyForcibly();
        if (!proceso.waitFor(5, TimeUnit.SECONDS)) throw ErrorCarga.indisponible();
      }
    }
  }

  private static void limpiar(Path dir) {
    ArchivoPreparado.eliminar(dir);
  }
}
