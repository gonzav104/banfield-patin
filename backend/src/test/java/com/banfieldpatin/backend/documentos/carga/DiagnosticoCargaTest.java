package com.banfieldpatin.backend.documentos.carga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.documentos.DocumentoMetadatos;
import com.banfieldpatin.backend.documentos.EstadoDocumento;
import com.banfieldpatin.backend.documentos.TipoDocumento;
import com.banfieldpatin.backend.documentos.almacen.AlmacenDocumentos;
import com.banfieldpatin.backend.documentos.almacen.DestinoPreparado;
import com.banfieldpatin.backend.documentos.almacen.FalloAlmacenDocumentos;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.VerificadorSesionVigente;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Los fallos de recuperación dejan una línea WARN con operación, fase, clase de la causa y código
 * saneado; jamás el mensaje de la excepción, rutas, nombres, SHA ni identificadores personales.
 */
@ExtendWith(OutputCaptureExtension.class)
class DiagnosticoCargaTest {
  private static final String SENSIBLE = "SENSIBLE-SINTETICO";
  private static final String LINEA = "Fallo en la carga documental";

  @TempDir Path temporal;

  private final UUID atleta = UUID.randomUUID();
  private final UUID idempotencia = UUID.randomUUID();
  private final DestinoPreparado destino =
      DestinoPreparado.desdeRegistro("bucket-sintetico", UUID.randomUUID());
  private final byte[] sha = "sha-sintetico-de-prueba-0123456".getBytes();
  private final byte[] contenido = imagen();
  private final OperacionesCargaJdbc.Operacion op =
      new OperacionesCargaJdbc.Operacion(
          UUID.randomUUID(),
          0,
          "RESERVADA",
          atleta,
          "OTRO",
          null,
          destino,
          sha,
          "image/png",
          contenido.length);
  private final OperacionesCargaJdbc operaciones = mock(OperacionesCargaJdbc.class);
  private final AlmacenDocumentos almacen = mock(AlmacenDocumentos.class);

  private static byte[] imagen() {
    try {
      return PreparadorDocumentoTest.imagen("png", 2, 2);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static MockMultipartFile archivo(byte[] contenido) {
    return new MockMultipartFile("archivo", "sintetico.png", "image/png", contenido);
  }

  private CargaPropiedades propiedades(int puerto) {
    return new CargaPropiedades(
        true,
        5_000_000,
        temporal,
        16_000_000,
        Duration.ofSeconds(10),
        Duration.ofSeconds(5),
        "127.0.0.1",
        puerto,
        2,
        Duration.ofMinutes(3));
  }

  /** Scanner sintético de una sola conexión que responde lo indicado. */
  private ServerSocket scanner(String respuesta) throws Exception {
    var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    Thread.ofVirtual()
        .start(
            () -> {
              try (var socket = server.accept();
                  var in = new DataInputStream(socket.getInputStream())) {
                in.readNBytes(10);
                var total = new ByteArrayOutputStream();
                int n;
                while ((n = in.readInt()) != 0) total.write(in.readNBytes(n));
                socket.getOutputStream().write(respuesta.getBytes());
              } catch (Exception ignorada) {
                /* sólo existe para esta prueba */
              }
            });
    return server;
  }

  private void prepararFlujoFeliz() {
    when(almacen.prepararDestino()).thenReturn(destino);
    when(operaciones.reservar(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new OperacionesCargaJdbc.Reserva(op, null));
    when(operaciones.iniciar(any(), any())).thenReturn(op);
    when(operaciones.almacenada(any())).thenReturn(op);
  }

  private CargaDocumentalService.Resultado cargar(CargaDocumentalService servicio) {
    return servicio.cargar(
        null, atleta, TipoDocumento.OTRO, null, idempotencia, archivo(contenido), null);
  }

  private static void sinDatosSensibles(String salida, Object... prohibidos) {
    assertThat(salida).doesNotContain(SENSIBLE);
    for (Object p : prohibidos) assertThat(salida).doesNotContain(p.toString());
  }

  @Test
  void fallaDePublicacionRegistraOperacionFaseYCausaSinMensaje(CapturedOutput salida)
      throws Exception {
    try (var server = scanner("stream: OK\0")) {
      prepararFlujoFeliz();
      when(operaciones.publicar(any(), any(), any()))
          .thenThrow(
              new IllegalStateException(
                  SENSIBLE + " " + temporal + " " + atleta + " " + idempotencia));
      var servicio = new CargaDocumentalService(operaciones, propiedades(server.getLocalPort()), almacen);

      assertThatThrownBy(() -> cargar(servicio))
          .isInstanceOfSatisfying(
              ExcepcionNegocio.class,
              e -> assertThat(e.getCodigo()).isEqualTo("RESULTADO_CARGA_INCIERTO"));

      assertThat(salida.getAll())
          .contains(LINEA)
          .contains("operacion=" + op.id())
          .contains("fase=publicar")
          .contains("causa=IllegalStateException")
          .contains("codigo=-");
      sinDatosSensibles(
          salida.getAll(),
          temporal,
          atleta,
          idempotencia,
          destino.objetoIdInterno(),
          destino.bucketInterno(),
          "sintetico.png",
          "sha-sintetico");
    }
  }

  @Test
  void fallaDeTransferenciaRegistraElCodigoSaneadoDelAlmacen(CapturedOutput salida)
      throws Exception {
    try (var server = scanner("stream: OK\0")) {
      prepararFlujoFeliz();
      var ctor =
          FalloAlmacenDocumentos.class.getDeclaredConstructor(
              FalloAlmacenDocumentos.Codigo.class, boolean.class);
      ctor.setAccessible(true);
      doThrow(ctor.newInstance(FalloAlmacenDocumentos.Codigo.TIMEOUT, true))
          .when(almacen)
          .crear(any(), any(), anyLong(), anyString());
      var servicio = new CargaDocumentalService(operaciones, propiedades(server.getLocalPort()), almacen);

      assertThatThrownBy(() -> cargar(servicio)).isInstanceOf(ExcepcionNegocio.class);

      assertThat(salida.getAll())
          .contains(LINEA)
          .contains("operacion=" + op.id())
          .contains("fase=transferir")
          .contains("causa=FalloAlmacenDocumentos")
          .contains("codigo=TIMEOUT");
      sinDatosSensibles(salida.getAll(), temporal, atleta, destino.objetoIdInterno());
    }
  }

  @Test
  void fallaAntesDeExistirLaOperacionNoInventaIdentificador(CapturedOutput salida)
      throws Exception {
    when(almacen.prepararDestino()).thenReturn(destino);
    when(operaciones.reservar(any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new CannotCreateTransactionException(SENSIBLE + " " + temporal));
    var servicio = new CargaDocumentalService(operaciones, propiedades(3310), almacen);

    assertThatThrownBy(() -> cargar(servicio))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class,
            e -> assertThat(e.getCodigo()).isEqualTo("CARGA_NO_DISPONIBLE"));

    assertThat(salida.getAll())
        .contains(LINEA)
        .contains("operacion=-")
        .contains("fase=reservar")
        .contains("causa=CannotCreateTransactionException");
    sinDatosSensibles(salida.getAll(), temporal, atleta, idempotencia);
  }

  @Test
  void perdidaDeCasConLaOperacionReservadaSeRegistra(CapturedOutput salida) throws Exception {
    try (var server = scanner("stream: OK\0")) {
      prepararFlujoFeliz();
      when(operaciones.iniciar(any(), any())).thenThrow(ErrorCarga.conflicto());
      var servicio = new CargaDocumentalService(operaciones, propiedades(server.getLocalPort()), almacen);

      assertThatThrownBy(() -> cargar(servicio))
          .isInstanceOfSatisfying(
              ExcepcionNegocio.class, e -> assertThat(e.getEstado().value()).isEqualTo(409));
    }
    assertThat(salida.getAll())
        .contains("operacion=" + op.id())
        .contains("fase=iniciar")
        .contains("codigo=OPERACION_NO_REPETIBLE");
  }

  @Test
  void rechazosEsperadosDelClienteNoGeneranAdvertencias(CapturedOutput salida) throws Exception {
    try (var server = scanner("stream: Eicar-Sintetico FOUND\0")) {
      prepararFlujoFeliz();
      var servicio = new CargaDocumentalService(operaciones, propiedades(server.getLocalPort()), almacen);

      assertThatThrownBy(() -> cargar(servicio))
          .isInstanceOfSatisfying(
              ExcepcionNegocio.class, e -> assertThat(e.getEstado().value()).isEqualTo(422));
    }
    assertThat(salida.getAll()).doesNotContain(LINEA);
  }

  @Test
  void cargaExitosaNoGeneraAdvertencias(CapturedOutput salida) throws Exception {
    try (var server = scanner("stream: OK\0")) {
      prepararFlujoFeliz();
      when(operaciones.publicar(any(), any(), any()))
          .thenReturn(
              new DocumentoMetadatos(
                  op.id(),
                  TipoDocumento.OTRO,
                  EstadoDocumento.CARGADO,
                  null,
                  Instant.now(),
                  true,
                  null,
                  null));
      var servicio = new CargaDocumentalService(operaciones, propiedades(server.getLocalPort()), almacen);
      assertThat(cargar(servicio).repetida()).isFalse();
    }
    assertThat(salida.getAll()).doesNotContain(LINEA);
  }

  @Test
  void compensacionFallidaSeRegistraSinMensajeNiLanzarExcepcion(CapturedOutput salida) {
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any()))
        .thenThrow(new CannotCreateTransactionException(SENSIBLE + " " + temporal));
    var reales =
        new OperacionesCargaJdbc(
            mock(JdbcClient.class),
            manager,
            Clock.systemUTC(),
            mock(VerificadorSesionVigente.class),
            mock(DeportistaRepository.class),
            mock(FamiliaDeportistaRepository.class),
            mock(AuditoriaService.class),
            propiedades(3310));

    assertThatCode(() -> reales.incierta(op)).doesNotThrowAnyException();
    assertThatCode(() -> reales.cerrarAntesDeTransferir(op)).doesNotThrowAnyException();

    assertThat(salida.getAll())
        .contains("operacion=" + op.id())
        .contains("fase=reconciliacion")
        .contains("fase=cierre")
        .contains("causa=CannotCreateTransactionException");
    sinDatosSensibles(salida.getAll(), temporal, atleta, destino.objetoIdInterno(), "sha-sintetico");
  }
}
