package com.banfieldpatin.backend.documentos.carga;

import static org.assertj.core.api.Assertions.*;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;

class PreparadorDocumentoTest {
  @TempDir Path temporal;

  static CargaPropiedades propiedades(Path dir, long limite) {
    return new CargaPropiedades(
        true,
        limite,
        dir,
        12000000,
        Duration.ofSeconds(10),
        Duration.ofSeconds(2),
        "127.0.0.1",
        3310,
        2,
        Duration.ofMinutes(3));
  }

  static byte[] imagen(String formato, int ancho, int alto) throws Exception {
    var salida = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB), formato, salida);
    return salida.toByteArray();
  }

  static byte[] pdf(String peligro) throws Exception {
    try (var doc = new PDDocument();
        var salida = new ByteArrayOutputStream()) {
      doc.addPage(new PDPage());
      if (peligro.equals("cifrado"))
        doc.protect(
            new StandardProtectionPolicy(
                "propietario-sintetico", "clave-sintetica", new AccessPermission()));
      if (peligro.equals("javascript")) {
        var a = new COSDictionary();
        a.setName(COSName.S, "JavaScript");
        a.setString(COSName.getPDFName("JS"), "app.alert('sintetico')");
        doc.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("OpenAction"), a);
      }
      if (peligro.equals("adjunto")) {
        var a = new COSDictionary();
        a.setItem(COSName.getPDFName("EmbeddedFiles"), new COSDictionary());
        doc.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Names"), a);
      }
      doc.save(salida);
      return salida.toByteArray();
    }
  }

  @ParameterizedTest
  @MethodSource("validos")
  void aceptaContenidoSinteticoRealYEliminaTemporal(String formato) throws Exception {
    byte[] data = formato.equals("pdf") ? pdf("") : imagen(formato, 2, 2);
    String mime = formato.equals("pdf") ? "application/pdf" : "image/" + formato;
    Path file;
    try (var a =
        new PreparadorDocumento(propiedades(temporal, 15000000))
            .preparar(new MockMultipartFile("archivo", "documento." + formato, mime, data))) {
      file = a.ruta;
      assertThat(a.mime).isEqualTo(mime);
      assertThat(a.bytes).isEqualTo(data.length);
      assertThat(a.sha).isEqualTo(java.security.MessageDigest.getInstance("SHA-256").digest(data));
      assertThat(a.toString()).doesNotContain("documento.", "/", mime);
      assertThat(Files.getPosixFilePermissions(file))
          .containsExactlyInAnyOrder(
              java.nio.file.attribute.PosixFilePermission.OWNER_READ,
              java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    }
    assertThat(file).doesNotExist();
    try (var ds = Files.list(temporal)) {
      assertThat(ds.count()).isZero();
    }
  }

  static Stream<String> validos() {
    return Stream.of("pdf", "png", "jpeg");
  }

  @ParameterizedTest
  @MethodSource("invalidos")
  void rechazaAntesDePublicar(String caso) throws Exception {
    byte[] data = imagen("png", 2, 2);
    String mime = "image/png", nombre = "documento.png";
    long limite = 15000000;
    switch (caso) {
      case "vacio" -> data = new byte[0];
      case "exceso" -> limite = 5;
      case "mime" -> mime = "application/pdf";
      case "extension" -> nombre = "documento.exe";
      case "cabecera" -> data = "no es imagen".getBytes();
      case "truncado" -> data = java.util.Arrays.copyOf(data, data.length - 4);
      case "crc" -> data[40] ^= 1;
      case "cola" -> {
        byte[] extra = java.util.Arrays.copyOf(data, data.length + 7);
        extra[data.length] = 'X';
        data = extra;
      }
      case "dimensiones" -> data = imagen("png", 4000, 4000);
      case "jpeg" -> {
        data = imagen("jpeg", 2, 2);
        data[data.length - 1] = 0;
        mime = "image/jpeg";
        nombre = "documento.jpeg";
      }
      case "pdf" -> {
        data = "%PDF-1.7\ncontenido truncado\n%%EOF".getBytes();
        mime = "application/pdf";
        nombre = "documento.pdf";
      }
      default -> {
        data = pdf(caso);
        mime = "application/pdf";
        nombre = "documento.pdf";
      }
    }
    var input = new MockMultipartFile("archivo", nombre, mime, data);
    long max = limite;
    assertThatThrownBy(() -> new PreparadorDocumento(propiedades(temporal, max)).preparar(input))
        .isInstanceOf(ExcepcionNegocio.class);
    try (var ds = Files.list(temporal)) {
      assertThat(ds.count()).isZero();
    }
  }

  static Stream<String> invalidos() {
    return Stream.of(
        "vacio",
        "exceso",
        "mime",
        "extension",
        "cabecera",
        "truncado",
        "crc",
        "cola",
        "dimensiones",
        "jpeg",
        "pdf",
        "cifrado",
        "javascript",
        "adjunto");
  }

  @Test
  void rechazaXrefInconsistenteSinRepararlo() throws Exception {
    String original = new String(pdf(""), java.nio.charset.StandardCharsets.ISO_8859_1);
    var matcher = java.util.regex.Pattern.compile("startxref\\s+(\\d+)").matcher(original);
    assertThat(matcher.find()).isTrue();
    String roto =
        original.substring(0, matcher.start(1))
            + "0".repeat(matcher.group(1).length())
            + original.substring(matcher.end(1));
    var file =
        new MockMultipartFile(
            "archivo",
            "sintetico.pdf",
            "application/pdf",
            roto.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    assertThatThrownBy(
            () -> new PreparadorDocumento(propiedades(temporal, 15000000)).preparar(file))
        .isInstanceOf(ExcepcionNegocio.class);
  }

  static byte[] pngConPaleta(boolean tardia) throws Exception {
    byte[] original = imagen("png", 2, 2);
    var chunk = new ByteArrayOutputStream();
    var out = new java.io.DataOutputStream(chunk);
    out.writeInt(3);
    byte[] tipo = "PLTE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] rgb = {0, 20, 30};
    out.write(tipo);
    out.write(rgb);
    var crc = new java.util.zip.CRC32();
    crc.update(tipo);
    crc.update(rgb);
    out.writeInt((int) crc.getValue());
    int posicion = tardia ? original.length - 12 : 33;
    var png = new ByteArrayOutputStream();
    png.write(original, 0, posicion);
    png.write(chunk.toByteArray());
    png.write(original, posicion, original.length - posicion);
    return png.toByteArray();
  }

  @Test
  void rechazaPaletaPngDespuesDeDatosPeroConservaLaValida() throws Exception {
    var preparador = new PreparadorDocumento(propiedades(temporal, 15000000));
    try (var valido =
        preparador.preparar(
            new MockMultipartFile("archivo", "sintetico.png", "image/png", pngConPaleta(false)))) {
      assertThat(valido.bytes).isPositive();
    }
    assertThatThrownBy(
            () ->
                preparador.preparar(
                    new MockMultipartFile(
                        "archivo", "sintetico.png", "image/png", pngConPaleta(true))))
        .isInstanceOf(ExcepcionNegocio.class);
  }

  /** Enlaza los valores predeterminados reales, como Spring, en lugar de repetirlos en la prueba. */
  static CargaPropiedades porDefecto(Path dir) {
    return new org.springframework.boot.context.properties.bind.Binder(
            new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                java.util.Map.of("banfield.documentos.carga.temporales", dir.toString())))
        .bind("banfield.documentos.carga", CargaPropiedades.class)
        .get();
  }

  private void acepta(String nombre, String mime, byte[] data) {
    try (var a =
        new PreparadorDocumento(porDefecto(temporal))
            .preparar(new MockMultipartFile("archivo", nombre, mime, data))) {
      assertThat(a.bytes).isEqualTo(data.length);
    }
  }

  private void rechaza(String nombre, String mime, byte[] data) throws Exception {
    var input = new MockMultipartFile("archivo", nombre, mime, data);
    assertThatThrownBy(() -> new PreparadorDocumento(porDefecto(temporal)).preparar(input))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class, e -> assertThat(e.getEstado().value()).isEqualTo(422));
    try (var ds = Files.list(temporal)) {
      assertThat(ds.count()).isZero();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"4032,3024", "4080,3072", "4608,3456", "4000,4000"})
  void aceptaFotosHabitualesDeCelularConElLimitePorDefecto(int ancho, int alto)
      throws Exception {
    acepta("foto.jpg", "image/jpeg", imagen("jpeg", ancho, alto));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"jpeg,4001,4000", "png,4001,4000", "png,16001,1"})
  void rechazaImagenesQueSuperanLosLimitesPorDefecto(String formato, int ancho, int alto)
      throws Exception {
    rechaza("foto." + formato, "image/" + formato, imagen(formato, ancho, alto));
  }
}
