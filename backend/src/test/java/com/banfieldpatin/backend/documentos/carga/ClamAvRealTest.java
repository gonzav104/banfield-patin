package com.banfieldpatin.backend.documentos.carga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.DocumentoMetadatos;
import com.banfieldpatin.backend.documentos.EstadoDocumento;
import com.banfieldpatin.backend.documentos.TipoDocumento;
import com.banfieldpatin.backend.documentos.almacen.AlmacenDocumentos;
import com.banfieldpatin.backend.documentos.almacen.DestinoPreparado;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Verificación contra un ClamAV REAL (clamd en Docker local), opt-in: no corre en las suites
 * predeterminadas ni en CI. Solo contenido sintético y la cadena de prueba EICAR.
 *
 * <p>Levantar el servicio y ejecutar: ver "Verificación real de ClamAV" en docs/DOCUMENTOS.md.
 * Propiedades: {@code clamav.host} (127.0.0.1), {@code clamav.puerto} (13310) y
 * {@code clamav.contenedor} (banfield-clamav-test) para las pruebas de caída y pausa.
 */
@Tag("clamav-real")
@ExtendWith(OutputCaptureExtension.class)
class ClamAvRealTest {
  static final String HOST = System.getProperty("clamav.host", "127.0.0.1");
  static final int PUERTO = Integer.getInteger("clamav.puerto", 13310);
  static final String CONTENEDOR = System.getProperty("clamav.contenedor", "banfield-clamav-test");

  @TempDir Path temporal;

  @BeforeAll
  static void clamavDisponible() throws Exception {
    // Falla (no se omite) si el servicio opt-in no está levantado: sería una falsa verificación.
    assertThat(comando("zPING\0")).as("ClamAV no responde en %s:%s", HOST, PUERTO).isEqualTo("PONG");
    assertThat(comando("zVERSION\0")).startsWith("ClamAV ");
    registrarMarcadorSintetico();
  }

  /**
   * La firma EICAR solo se reconoce como archivo completo, nunca incrustada. Para probar un
   * documento válido que el scanner marca como peligroso se registra una firma sintética propia en
   * el contenedor descartable (se pierde al eliminarlo) y se recarga la base.
   */
  static final String MARCADOR = "BANFIELD-U7A-MARCADOR-SINTETICO";

  static void registrarMarcadorSintetico() throws Exception {
    String hex = java.util.HexFormat.of().formatHex(MARCADOR.getBytes(StandardCharsets.US_ASCII));
    docker(
        "exec", CONTENEDOR, "sh", "-c",
        "printf '%s' 'Banfield.U7a.Marcador:0:*:" + hex + "' > /var/lib/clamav/banfield-u7a.ndb");
    comando("zRELOAD\0");
    long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    while (System.nanoTime() < fin) {
      try {
        if (consultarInstream(("xx" + MARCADOR + "yy").getBytes(StandardCharsets.US_ASCII))
            .contains("FOUND")) return;
      } catch (Exception ex) {
        /* recargando */
      }
      Thread.sleep(1000);
    }
    throw new AssertionError("No se pudo registrar la firma sintética en " + CONTENEDOR);
  }

  static String consultarInstream(byte[] contenido) throws Exception {
    try (var socket = new java.net.Socket(HOST, PUERTO)) {
      socket.setSoTimeout(10000);
      var out = new DataOutputStream(socket.getOutputStream());
      out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
      out.writeInt(contenido.length);
      out.write(contenido);
      out.writeInt(0);
      out.flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
    }
  }

  /** Cadena de prueba estándar EICAR, armada en tiempo de ejecución. */
  static byte[] eicar() {
    String a = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-";
    String b = "ANTIVIRUS-TEST-FILE!$H+H*";
    return (a + b).getBytes(StandardCharsets.US_ASCII);
  }

  static String comando(String orden) throws Exception {
    try (var socket = new java.net.Socket(HOST, PUERTO)) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write(orden.getBytes(StandardCharsets.US_ASCII));
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
          .replace("\0", "")
          .trim();
    }
  }

  static String docker(String... args) throws Exception {
    var cmd = new java.util.ArrayList<String>();
    cmd.add("docker");
    cmd.addAll(java.util.List.of(args));
    var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    String salida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
    return salida.trim();
  }

  @AfterEach
  void dejarElServicioEnMarcha() throws Exception {
    // Las pruebas de pausa y caída no deben dejar el servicio inutilizable para las demás.
    try {
      docker("unpause", CONTENEDOR);
    } catch (Throwable ignorada) {
      /* no estaba en pausa */
    }
    if (!"running".equals(docker("inspect", "-f", "{{.State.Status}}", CONTENEDOR))) {
      docker("start", CONTENEDOR);
    }
    long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < fin) {
      try {
        if (comando("zPING\0").equals("PONG")) return;
      } catch (Exception ex) {
        Thread.sleep(500);
      }
    }
    throw new AssertionError("ClamAV no volvió a responder");
  }

  CargaPropiedades propiedades(int puerto, long maxBytes, Duration plazo) {
    return new CargaPropiedades(
        true,
        maxBytes,
        temporal,
        16_000_000,
        Duration.ofSeconds(10),
        plazo,
        HOST,
        puerto,
        2,
        Duration.ofMinutes(3));
  }

  Path archivo(String nombre, byte[] contenido) throws Exception {
    return Files.write(temporal.resolve(nombre), contenido);
  }

  static byte[] aleatorio(int n) {
    byte[] b = new byte[n];
    new Random(42).nextBytes(b);
    return b;
  }

  static int puertoCerrado() throws Exception {
    try (var s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      return s.getLocalPort();
    }
  }

  // ---------------------------------------------------------------- resultados del scanner

  @Test
  void unArchivoLimpioSeReconoceComoLimpio() throws Exception {
    var scanner = new ClamAvPrivado(propiedades(PUERTO, 5_000_000, Duration.ofSeconds(20)));
    assertThatCode(() -> scanner.escanear(archivo("limpio.bin", aleatorio(2048))))
        .doesNotThrowAnyException();
    assertThatCode(() -> scanner.escanear(archivo("texto.txt", "contenido inofensivo".getBytes())))
        .doesNotThrowAnyException();
  }

  @Test
  void eicarSeDetectaYSeTraduceAlCodigoDeNegocioSinFiltrarLaFirma(CapturedOutput salida)
      throws Exception {
    var scanner = new ClamAvPrivado(propiedades(PUERTO, 5_000_000, Duration.ofSeconds(20)));
    assertThatThrownBy(() -> scanner.escanear(archivo("sospechoso.bin", eicar())))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class,
            e -> {
              assertThat(e.getEstado().value()).isEqualTo(422);
              assertThat(e.getCodigo()).isEqualTo("ARCHIVO_PELIGROSO");
              assertThat(e.getMessage()).doesNotContainIgnoringCase("eicar").doesNotContain("sospechoso");
              assertThat(e.getCause()).isNull();
            });
    assertThat(salida.getAll())
        .doesNotContainIgnoringCase("eicar")
        .doesNotContain("sospechoso.bin")
        .doesNotContain(temporal.toString());
  }

  @Test
  void contenidoPeligrosoEnUnArchivoGrandeYEnUnZipSeDetecta() throws Exception {
    var scanner = new ClamAvPrivado(propiedades(PUERTO, 5_000_000, Duration.ofSeconds(20)));
    // Marcador en medio de 600 KB: se transmite en decenas de bloques del protocolo.
    var grande = new ByteArrayOutputStream();
    grande.write(aleatorio(300_000));
    grande.write(MARCADOR.getBytes(StandardCharsets.US_ASCII));
    grande.write(aleatorio(300_000));
    assertThatThrownBy(() -> scanner.escanear(archivo("grande.bin", grande.toByteArray())))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class, e -> assertThat(e.getCodigo()).isEqualTo("ARCHIVO_PELIGROSO"));

    // EICAR dentro de un ZIP con relleno aleatorio sin comprimir.
    var zip = new ByteArrayOutputStream();
    try (var z = new ZipOutputStream(zip)) {
      z.putNextEntry(new ZipEntry("relleno.bin"));
      z.write(aleatorio(300_000));
      z.closeEntry();
      z.putNextEntry(new ZipEntry("interno.txt"));
      z.write(eicar());
      z.closeEntry();
    }
    assertThatThrownBy(() -> scanner.escanear(archivo("comprimido.zip", zip.toByteArray())))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class, e -> assertThat(e.getCodigo()).isEqualTo("ARCHIVO_PELIGROSO"));
  }

  // ---------------------------------------------------------------- límites de tamaño

  @Test
  void elLimiteDeTamanioSeRespetaEnElBordeConUnClamdReal() throws Exception {
    var scanner = new ClamAvPrivado(propiedades(PUERTO, 1_000_000, Duration.ofSeconds(20)));
    assertThatCode(() -> scanner.escanear(archivo("justo.bin", aleatorio(1_000_000))))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> scanner.escanear(archivo("excedido.bin", aleatorio(1_000_001))))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class,
            e -> {
              assertThat(e.getEstado().value()).isEqualTo(413);
              assertThat(e.getCodigo()).isEqualTo("ARCHIVO_DEMASIADO_GRANDE");
            });
  }

  @Test
  void elTamanioMaximoPorDefectoDeCincoMegabytesPasaPorElProtocoloReal() throws Exception {
    var scanner = new ClamAvPrivado(propiedades(PUERTO, 5_000_000, Duration.ofSeconds(20)));
    long inicio = System.nanoTime();
    assertThatCode(() -> scanner.escanear(archivo("cinco.bin", aleatorio(5_000_000))))
        .doesNotThrowAnyException();
    assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(20));
  }

  // ---------------------------------------------------------------- indisponibilidad y errores

  @Test
  void sinServicioEscuchandoFallaCerradoConElCodigoDeNegocio(CapturedOutput salida)
      throws Exception {
    var scanner = new ClamAvPrivado(propiedades(puertoCerrado(), 5_000_000, Duration.ofSeconds(5)));
    long inicio = System.nanoTime();
    assertThatThrownBy(() -> scanner.escanear(archivo("limpio.bin", aleatorio(1024))))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class,
            e -> {
              assertThat(e.getEstado().value()).isEqualTo(503);
              assertThat(e.getCodigo()).isEqualTo("CARGA_NO_DISPONIBLE");
              assertThat(e.getCause()).isNull();
            });
    assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(5));
    assertThat(salida.getAll()).doesNotContain("limpio.bin").doesNotContain(temporal.toString());
  }

  @Test
  void unServicioPausadoAgotaElPlazoYFallaCerradoSinColgarse() throws Exception {
    assumeThat(docker("inspect", "-f", "{{.State.Status}}", CONTENEDOR)).isEqualTo("running");
    var scanner = new ClamAvPrivado(propiedades(PUERTO, 5_000_000, Duration.ofSeconds(2)));
    docker("pause", CONTENEDOR);
    long inicio = System.nanoTime();
    assertThatThrownBy(() -> scanner.escanear(archivo("limpio.bin", aleatorio(1024))))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class,
            e -> {
              assertThat(e.getEstado().value()).isEqualTo(503);
              assertThat(e.getCodigo()).isEqualTo("CARGA_NO_DISPONIBLE");
            });
    var transcurrido = Duration.ofNanos(System.nanoTime() - inicio);
    assertThat(transcurrido).as("respeta el plazo total").isLessThan(Duration.ofSeconds(5));
    // Aún con el contenido peligroso, un scanner que no responde nunca acepta el archivo.
    assertThatThrownBy(() -> scanner.escanear(archivo("eicar-pausa.bin", eicar())))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class, e -> assertThat(e.getEstado().value()).isEqualTo(503));
  }

  @Test
  void unServicioDetenidoDuranteLaPruebaFallaCerradoYSeRecupera() throws Exception {
    assumeThat(docker("inspect", "-f", "{{.State.Status}}", CONTENEDOR)).isEqualTo("running");
    var scanner = new ClamAvPrivado(propiedades(PUERTO, 5_000_000, Duration.ofSeconds(5)));
    docker("stop", "-t", "1", CONTENEDOR);
    assertThatThrownBy(() -> scanner.escanear(archivo("limpio.bin", aleatorio(1024))))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class, e -> assertThat(e.getEstado().value()).isEqualTo(503));
    docker("start", CONTENEDOR);
    long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    boolean listo = false;
    while (!listo && System.nanoTime() < fin) {
      try {
        listo = comando("zPING\0").equals("PONG");
      } catch (Exception ex) {
        Thread.sleep(500);
      }
    }
    assertThat(listo).isTrue();
    assertThatCode(() -> scanner.escanear(archivo("tras-reinicio.bin", aleatorio(1024))))
        .doesNotThrowAnyException();
  }

  @Test
  void unPuertoQueNoEsClamavNoSeTomaPorLimpio() throws Exception {
    // Un servicio que acepta la conexión y responde otra cosa (error de comunicación).
    try (var falso = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      Thread.ofVirtual()
          .start(
              () -> {
                try (var s = falso.accept()) {
                  s.getInputStream().readNBytes(10);
                  s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                } catch (Exception ignorada) {
                  /* servidor de prueba */
                }
              });
      var scanner =
          new ClamAvPrivado(propiedades(falso.getLocalPort(), 5_000_000, Duration.ofSeconds(3)));
      assertThatThrownBy(() -> scanner.escanear(archivo("limpio.bin", aleatorio(512))))
          .isInstanceOfSatisfying(
              ExcepcionNegocio.class, e -> assertThat(e.getEstado().value()).isEqualTo(503));
    }
  }

  // ---------------------------------------------------------------- ninguna ruta acepta sin inspección

  private static byte[] pngConTexto(byte[] texto) throws Exception {
    var base = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "png",
        base);
    byte[] png = base.toByteArray();
    // Inserta un fragmento auxiliar tEXt (válido para el inspector) antes de IEND.
    int iend = png.length - 12;
    var chunk = new ByteArrayOutputStream();
    var dato = new ByteArrayOutputStream();
    dato.write("Comment".getBytes(StandardCharsets.US_ASCII));
    dato.write(0);
    dato.write(texto);
    var tipoYDato = new ByteArrayOutputStream();
    tipoYDato.write("tEXt".getBytes(StandardCharsets.US_ASCII));
    tipoYDato.write(dato.toByteArray());
    var crc = new CRC32();
    crc.update(tipoYDato.toByteArray());
    var out = new DataOutputStream(chunk);
    out.writeInt(dato.size());
    out.write(tipoYDato.toByteArray());
    out.writeInt((int) crc.getValue());
    var resultado = new ByteArrayOutputStream();
    resultado.write(png, 0, iend);
    resultado.write(chunk.toByteArray());
    resultado.write(png, iend, 12);
    return resultado.toByteArray();
  }

  private record Servicio(
      CargaDocumentalService servicio, OperacionesCargaJdbc operaciones, AlmacenDocumentos almacen) {}

  private Servicio servicio(int puerto, Duration plazo) throws Exception {
    var operaciones = mock(OperacionesCargaJdbc.class);
    var almacen = mock(AlmacenDocumentos.class);
    var destino = DestinoPreparado.desdeRegistro("bucket-sintetico", UUID.randomUUID());
    var op =
        new OperacionesCargaJdbc.Operacion(
            UUID.randomUUID(), 0, "RESERVADA", UUID.randomUUID(), "OTRO", null, destino,
            new byte[32], "image/png", 1);
    when(almacen.prepararDestino()).thenReturn(destino);
    when(operaciones.reservar(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new OperacionesCargaJdbc.Reserva(op, null));
    when(operaciones.iniciar(any(), any())).thenReturn(op);
    when(operaciones.almacenada(any())).thenReturn(op);
    when(operaciones.publicar(any(), any(), any()))
        .thenReturn(
            new DocumentoMetadatos(
                op.id(), TipoDocumento.OTRO, EstadoDocumento.CARGADO, null, Instant.now(), true,
                null, null));
    return new Servicio(
        new CargaDocumentalService(operaciones, propiedades(puerto, 5_000_000, plazo), almacen),
        operaciones,
        almacen);
  }

  private CargaDocumentalService.Resultado cargar(Servicio s, byte[] contenido) {
    return s.servicio()
        .cargar(
            null, UUID.randomUUID(), TipoDocumento.OTRO, null, UUID.randomUUID(),
            new MockMultipartFile("archivo", "sintetico.png", "image/png", contenido), null);
  }

  @Test
  void unDocumentoLimpioSeTransfiereYSePublicaTrasLaInspeccionReal() throws Exception {
    var s = servicio(PUERTO, Duration.ofSeconds(20));
    assertThat(cargar(s, pngConTexto("inofensivo".getBytes())).repetida()).isFalse();
    verify(s.almacen(), times(1)).crear(any(), any(), anyLong(), anyString());
  }

  @Test
  void unDocumentoValidoMarcadoComoPeligrosoNuncaLlegaAStorage() throws Exception {
    var s = servicio(PUERTO, Duration.ofSeconds(20));
    assertThatThrownBy(() -> cargar(s, pngConTexto(MARCADOR.getBytes(StandardCharsets.US_ASCII))))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class,
            e -> {
              assertThat(e.getEstado().value()).isEqualTo(422);
              assertThat(e.getCodigo()).isEqualTo("ARCHIVO_PELIGROSO");
            });
    verify(s.almacen(), never()).crear(any(), any(), anyLong(), anyString());
    verify(s.operaciones(), never()).publicar(any(), any(), any());
    verify(s.operaciones(), times(1)).cerrarAntesDeTransferir(any());
  }

  @Test
  void conElScannerCaidoNingunDocumentoLlegaAStorageNiSePublica() throws Exception {
    var s = servicio(puertoCerrado(), Duration.ofSeconds(3));
    assertThatThrownBy(() -> cargar(s, pngConTexto("inofensivo".getBytes())))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class,
            e -> {
              assertThat(e.getEstado().value()).isEqualTo(503);
              assertThat(e.getCodigo()).isEqualTo("CARGA_NO_DISPONIBLE");
            });
    verify(s.almacen(), never()).crear(any(), any(), anyLong(), anyString());
    verify(s.operaciones(), never()).publicar(any(), any(), any());
    verify(s.operaciones(), never()).iniciar(any(), any());
  }
}
