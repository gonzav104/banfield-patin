package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.*;

import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.usuarios.Rol;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;

/** Demostración HTTP real, base exclusiva PG17 y proveedores exclusivamente sintéticos. */
@Tag("db")
@ActiveProfiles({"test", "e2e"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.context.annotation.Import(CargaDocumentalHttpDbTest.RelojPrueba.class)
@org.junit.jupiter.api.extension.ExtendWith(
    org.springframework.boot.test.system.OutputCaptureExtension.class)
class CargaDocumentalHttpDbTest {
  static final String BASE = PostgresDescartable.crearBase("carga_http");
  static final HttpServer STORAGE;
  static final ServerSocket SCANNER;
  static final AtomicInteger POSTS = new AtomicInteger();
  static final AtomicReference<String> RESPUESTA = new AtomicReference<>("stream: OK\0");
  static final AtomicReference<Runnable> HOOK_STORAGE = new AtomicReference<>(() -> {}),
      HOOK_SCANNER = new AtomicReference<>(() -> {});
  static final AtomicInteger ESTADO_STORAGE = new AtomicInteger(200);
  static final Path TEMPORAL;
  static final AtomicLong DESFASE = new AtomicLong();

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class RelojPrueba {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    java.time.Clock relojPrueba() {
      return new java.time.Clock() {
        @Override
        public java.time.ZoneId getZone() {
          return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId z) {
          return this;
        }

        @Override
        public java.time.Instant instant() {
          return java.time.Instant.now().plusSeconds(DESFASE.get());
        }
      };
    }
  }

  static {
    try {
      TEMPORAL = Files.createTempDirectory("carga-http-");
      STORAGE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      STORAGE.createContext(
          "/storage/v1/",
          x -> {
            String path = x.getRequestURI().getPath();
            String body;
            if (x.getRequestMethod().equals("GET")) {
              body = "{\"id\":\"privado\",\"public\":false}";
            } else {
              POSTS.incrementAndGet();
              x.getRequestBody().readAllBytes();
              HOOK_STORAGE.get().run();
              body = "{\"Key\":\"" + path.substring(path.indexOf("object/") + 7) + "\"}";
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(
                x.getRequestMethod().equals("GET") ? 200 : ESTADO_STORAGE.get(), b.length);
            x.getResponseBody().write(b);
            x.close();
          });
      STORAGE.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      STORAGE.start();
      SCANNER = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
      Thread.ofVirtual()
          .start(
              () -> {
                while (!SCANNER.isClosed())
                  try {
                    var socket = SCANNER.accept();
                    Thread.ofVirtual()
                        .start(
                            () -> {
                              try (socket;
                                  var in = new DataInputStream(socket.getInputStream())) {
                                if (!Arrays.equals(in.readNBytes(10), "zINSTREAM\0".getBytes()))
                                  throw new IOException();
                                int n;
                                while ((n = in.readInt()) != 0) {
                                  if (n < 0 || n > 8192) throw new IOException();
                                  in.readNBytes(n);
                                }
                                HOOK_SCANNER.get().run();
                                socket
                                    .getOutputStream()
                                    .write(RESPUESTA.get().getBytes(StandardCharsets.US_ASCII));
                              } catch (Exception ignored) {
                              }
                            });
                  } catch (IOException ignored) {
                  }
              });
    } catch (IOException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> PostgresDescartable.urlDeBase(BASE));
    r.add("spring.datasource.username", PostgresDescartable::usuario);
    r.add("spring.datasource.password", PostgresDescartable::clave);
    r.add("spring.flyway.enabled", () -> true);
    r.add("banfield.documentos.carga.habilitada", () -> true);
    r.add("banfield.documentos.carga.temporales", () -> TEMPORAL.toString());
    r.add("banfield.documentos.carga.scanner-puerto", SCANNER::getLocalPort);
    r.add("banfield.documentos.carga.scanner-plazo", () -> "PT8S");
    r.add("banfield.documentos.storage.habilitado", () -> true);
    r.add(
        "banfield.documentos.storage.url",
        () -> "http://127.0.0.1:" + STORAGE.getAddress().getPort());
    r.add("banfield.documentos.storage.bucket", () -> "privado");
    r.add("banfield.documentos.storage.credencial", () -> "solo-sintetico-storage");
    r.add("banfield.documentos.storage.respuesta", () -> "PT2S");
  }

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.banfieldpatin.backend.compartido.auditoria.AuditoriaService auditoria;

  @Autowired javax.sql.DataSource fuente;
  @Autowired com.banfieldpatin.backend.familias.vinculos.VinculoAdminService vinculosAdmin;
  @Autowired JdbcClient jdbc;
  @Autowired ServicioTokens tokens;
  @LocalServerPort int puerto;
  UUID escuela, familia, usuario, atleta;
  String token;

  @BeforeEach
  void datos() {
    DESFASE.set(0);
    POSTS.set(0);
    ESTADO_STORAGE.set(200);
    RESPUESTA.set("stream: OK\0");
    HOOK_STORAGE.set(() -> {});
    HOOK_SCANNER.set(() -> {});
    var d = new DatosDb(jdbc);
    escuela = d.escuela("carga-" + UUID.randomUUID());
    familia = d.familia(escuela, "Familia sintética", true);
    usuario = d.usuarioFamilia(escuela, familia, "sintetico@example.invalid");
    atleta = d.deportista(escuela, "SINTETICO", "Prueba", "Atleta");
    d.vinculo(
        escuela,
        familia,
        atleta,
        "ACTIVO",
        true,
        d.admin(escuela, "admin-sintetico@example.invalid", true));
    token = tokens.emitir(usuario, Rol.FAMILIA, escuela, familia);
  }

  @AfterAll
  void cerrar() throws Exception {
    STORAGE.stop(0);
    SCANNER.close();
    if (fuente instanceof com.zaxxer.hikari.HikariDataSource pool) pool.close();
    PostgresDescartable.eliminarBase(BASE);
    try (var files = Files.walk(TEMPORAL)) {
      for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    }
  }

  static byte[] png() throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
    return out.toByteArray();
  }

  HttpResponse<String> cargar(UUID key, byte[] bytes) throws Exception {
    return cargar(key, bytes, token, true);
  }

  HttpResponse<String> cargar(UUID key, byte[] bytes, String sesion, boolean csrf)
      throws Exception {
    return cargarArchivo(key, bytes, sesion, csrf, "sintetico.png", "image/png", "OTRO");
  }

  HttpResponse<String> cargarArchivo(
      UUID key, byte[] bytes, String sesion, boolean csrf, String nombre, String mime, String tipo)
      throws Exception {
    var client = HttpClient.newHttpClient();
    var c =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + "/api/auth/csrf"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String value = JsonPath.read(c.body(), "$.token"),
        header = JsonPath.read(c.body(), "$.headerName");
    String cookie =
        c.headers().allValues("set-cookie").stream()
            .map(s -> s.split(";", 2)[0])
            .filter(s -> s.startsWith("XSRF-TOKEN="))
            .findFirst()
            .orElseThrow();
    String boundary = "sintetico-boundary";
    var body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"tipo\"\r\n\r\n"
                + tipo
                + "\r\n--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"archivo\"; filename=\""
                + nombre
                + "\"\r\nContent-Type: "
                + mime
                + "\r\n\r\n")
            .getBytes());
    body.write(bytes);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes());
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + puerto
                        + "/api/familia/deportistas/"
                        + atleta
                        + "/documentos"))
            .timeout(java.time.Duration.ofSeconds(20))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Idempotency-Key", key.toString())
            .header("Cookie", cookie + "; BP_SESION=" + sesion);
    if (csrf) request.header(header, cookie.substring(cookie.indexOf('=') + 1));
    return client.send(
        request.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  int documentos() {
    return jdbc.sql("SELECT count(*) FROM gestion_patin.documento WHERE escuela_id=:e")
        .param("e", escuela)
        .query(Integer.class)
        .single();
  }

  String fase(UUID key) {
    return jdbc.sql(
            "SELECT estado FROM gestion_patin.operacion_carga_documental WHERE escuela_id=:e AND"
                + " idempotencia=:k")
        .param("e", escuela)
        .param("k", key)
        .query(String.class)
        .single();
  }

  void sinFugas(HttpResponse<String> r) {
    assertThat(r.body())
        .doesNotContain(
            "privado", "storage", "solo-sintetico", "sintetico.png", "sha256", "reserva_hasta");
    assertThat(r.headers().firstValue("cache-control").orElse("")).contains("no-store");
  }

  @Test
  void cargaConfirmadaVisiblePorD1YRepeticionSinDuplicado() throws Exception {
    var key = UUID.randomUUID();
    byte[] file = png();
    var a = cargar(key, file);
    assertThat(a.statusCode()).as(a.body()).isEqualTo(201);
    sinFugas(a);
    assertThat(JsonPath.<String>read(a.body(), "$.estado")).isEqualTo("CARGADO");
    var b = cargar(key, file);
    assertThat(b.statusCode()).as(b.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(b.body(), "$.id"))
        .isEqualTo(JsonPath.<String>read(a.body(), "$.id"));
    assertThat(documentos()).isEqualTo(1);
    assertThat(POSTS.get()).isEqualTo(1);
    assertThat(fase(key)).isEqualTo("PUBLICADA");
    var get =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://127.0.0.1:"
                                + puerto
                                + "/api/familia/deportistas/"
                                + atleta
                                + "/documentos"))
                    .header("Cookie", "BP_SESION=" + token)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(get.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<Integer>read(get.body(), "$.totalElementos")).isEqualTo(1);
    sinFugas(get);
  }

  @Test
  void csrfPermaneceObligatorio() throws Exception {
    assertThat(cargar(UUID.randomUUID(), png(), token, false).statusCode()).isEqualTo(403);
    assertThat(documentos()).isZero();
    assertThat(POSTS.get()).isZero();
  }

  @Test
  void familiaAjenaYVinculoRevocadoNoInicianTransferencia() throws Exception {
    var d = new DatosDb(jdbc);
    UUID f = d.familia(escuela, "Otra sintética", true),
        u = d.usuarioFamilia(escuela, f, "otro@example.invalid");
    assertThat(
            cargar(UUID.randomUUID(), png(), tokens.emitir(u, Rol.FAMILIA, escuela, f), true)
                .statusCode())
        .isEqualTo(404);
    jdbc.sql("UPDATE gestion_patin.familia_deportista SET estado='REVOCADO' WHERE deportista_id=:d")
        .param("d", atleta)
        .update();
    assertThat(cargar(UUID.randomUUID(), png()).statusCode()).isEqualTo(404);
    assertThat(POSTS.get()).isZero();
    assertThat(documentos()).isZero();
  }

  @Test
  void archivoInvalidoNoPublica() throws Exception {
    assertThat(cargar(UUID.randomUUID(), "no es PNG".getBytes()).statusCode()).isEqualTo(422);
    assertThat(documentos()).isZero();
    assertThat(POSTS.get()).isZero();
  }

  @Test
  void scannerDetectaYFallaCerrado() throws Exception {
    RESPUESTA.set("stream: Firma-Sintetica FOUND\0");
    var r = cargar(UUID.randomUUID(), png());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(422);
    sinFugas(r);
    assertThat(documentos()).isZero();
    assertThat(POSTS.get()).isZero();
    RESPUESTA.set("stream: fallo interno ERROR\0");
    r = cargar(UUID.randomUUID(), png());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(503);
    sinFugas(r);
    assertThat(documentos()).isZero();
    assertThat(POSTS.get()).isZero();
  }

  @Test
  void storageInciertoNoSeReenviaNiBorra() throws Exception {
    ESTADO_STORAGE.set(503);
    var key = UUID.randomUUID();
    byte[] file = png();
    var r = cargar(key, file);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(503);
    assertThat(fase(key)).isEqualTo("RECONCILIANDO");
    assertThat(documentos()).isZero();
    assertThat(cargar(key, file).statusCode()).isEqualTo(409);
    assertThat(POSTS.get()).isEqualTo(1);
    sinFugas(r);
  }

  @Test
  void revocacionConfirmadaDuranteTransferenciaImpidePublicar() throws Exception {
    HOOK_STORAGE.set(
        () ->
            jdbc.sql(
                    "UPDATE gestion_patin.familia_deportista SET estado='REVOCADO' WHERE"
                        + " deportista_id=:d")
                .param("d", atleta)
                .update());
    var key = UUID.randomUUID();
    var r = cargar(key, png());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(404);
    assertThat(documentos()).isZero();
    assertThat(fase(key)).isEqualTo("RECONCILIANDO");
    assertThat(POSTS.get()).isEqualTo(1);
  }

  @Test
  void dosSolicitudesConLaMismaClaveNoDuplican() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    HOOK_SCANNER.set(
        () -> {
          entered.countDown();
          try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException();
          }
        });
    var key = UUID.randomUUID();
    byte[] file = png();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = pool.submit(() -> cargar(key, file));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      var second = cargar(key, file);
      release.countDown();
      assertThat(second.statusCode()).as(second.body()).isEqualTo(409);
      assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
      assertThat(documentos()).isEqualTo(1);
      assertThat(POSTS.get()).isEqualTo(1);
    } finally {
      release.countDown();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"jpeg", "pdf"})
  void cargaCompletaDeLosOtrosFormatos(String formato) throws Exception {
    byte[] bytes;
    if (formato.equals("pdf"))
      try (var d = new org.apache.pdfbox.pdmodel.PDDocument();
          var b = new ByteArrayOutputStream()) {
        d.addPage(new org.apache.pdfbox.pdmodel.PDPage());
        d.save(b);
        bytes = b.toByteArray();
      }
    else {
      var b = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpeg", b);
      bytes = b.toByteArray();
    }
    var r =
        cargarArchivo(
            UUID.randomUUID(),
            bytes,
            token,
            true,
            "sintetico." + formato,
            formato.equals("pdf") ? "application/pdf" : "image/jpeg",
            "OTRO");
    assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
    assertThat(documentos()).isEqualTo(1);
    sinFugas(r);
  }

  @Test
  void dniExistenteNoSeReemplazaNiIniciaOtraTransferencia() throws Exception {
    byte[] file = png();
    var key = UUID.randomUUID();
    var a =
        cargarArchivo(
            key, file, token, true, "sintetico.png", "image/png", "DNI_DEPORTISTA_FRENTE");
    assertThat(a.statusCode()).as(a.body()).isEqualTo(201);
    assertThat(
            cargarArchivo(
                    key, file, token, true, "sintetico.png", "image/png", "DNI_DEPORTISTA_FRENTE")
                .statusCode())
        .isEqualTo(200);
    var b =
        cargarArchivo(
            UUID.randomUUID(),
            file,
            token,
            true,
            "sintetico.png",
            "image/png",
            "DNI_DEPORTISTA_FRENTE");
    assertThat(b.statusCode()).as(b.body()).isEqualTo(409);
    assertThat(POSTS.get()).isEqualTo(1);
    assertThat(documentos()).isEqualTo(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"usuario", "familia", "escuela"})
  void desactivacionDuranteTransferenciaImpidePublicar(String entidad) throws Exception {
    HOOK_STORAGE.set(
        () -> {
          var d = new DatosDb(jdbc);
          switch (entidad) {
            case "usuario" -> d.desactivarUsuario(usuario);
            case "familia" -> d.desactivarFamilia(familia);
            default -> d.desactivarEscuela(escuela);
          }
        });
    var r = cargar(UUID.randomUUID(), png());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(401);
    assertThat(documentos()).isZero();
    assertThat(POSTS.get()).isEqualTo(1);
  }

  @Test
  void revocacionDuranteScannerNoIniciaStorageNiRetieneTransaccion() throws Exception {
    HOOK_SCANNER.set(
        () ->
            jdbc.sql(
                    "UPDATE gestion_patin.familia_deportista SET estado='REVOCADO' WHERE"
                        + " deportista_id=:d")
                .param("d", atleta)
                .update());
    var key = UUID.randomUUID();
    var r = cargar(key, png());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(404);
    assertThat(fase(key)).isEqualTo("CERRADA");
    assertThat(POSTS.get()).isZero();
    assertThat(documentos()).isZero();
  }

  @Test
  void timeoutStoragePreservaRegistroAunqueLlegueRespuestaTardia() throws Exception {
    var bloqueado = new CountDownLatch(1);
    var liberar = new CountDownLatch(1);
    HOOK_STORAGE.set(
        () -> {
          bloqueado.countDown();
          try {
            liberar.await(8, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    var key = UUID.randomUUID();
    try {
      var r = cargar(key, png());
      assertThat(bloqueado.getCount()).isZero();
      assertThat(r.statusCode()).as(r.body()).isEqualTo(503);
      assertThat(fase(key)).isEqualTo("RECONCILIANDO");
      assertThat(documentos()).isZero();
      assertThat(POSTS.get()).isEqualTo(1);
    } finally {
      liberar.countDown();
    }
  }

  @Test
  void trabajadorConVersionPerdidaNoPublicaTrasRespuestaTardia() throws Exception {
    HOOK_STORAGE.set(
        () ->
            jdbc.sql(
                    "UPDATE gestion_patin.operacion_carga_documental SET"
                        + " reserva_hasta=clock_timestamp()-interval '1 second',version=version+1"
                        + " WHERE escuela_id=:e AND estado='TRANSFIRIENDO'")
                .param("e", escuela)
                .update());
    var r = cargar(UUID.randomUUID(), png());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
    assertThat(documentos()).isZero();
    assertThat(POSTS.get()).isEqualTo(1);
  }

  @Test
  void falloDeAuditoriaReviertePublicacionSinBorrarObjeto(
      org.springframework.boot.test.system.CapturedOutput out) throws Exception {
    org.mockito.Mockito.doAnswer(
            inv -> {
              var e =
                  inv.getArgument(
                      0, com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria.class);
              if (e.accion()
                  == com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria
                      .DOCUMENTO_CARGADO)
                throw new org.springframework.dao.DataAccessResourceFailureException(
                    "SENSIBLE-SINTETICO-NO-LOG");
              return inv.callRealMethod();
            })
        .when(auditoria)
        .registrar(org.mockito.ArgumentMatchers.any());
    var key = UUID.randomUUID();
    var r = cargar(key, png());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(503);
    assertThat(documentos()).isZero();
    assertThat(fase(key)).isEqualTo("RECONCILIANDO");
    assertThat(POSTS.get()).isEqualTo(1);
    sinFugas(r);
    assertThat(out.getAll())
        .doesNotContain(
            "SENSIBLE-SINTETICO-NO-LOG", token, "solo-sintetico-storage", "sintetico.png");
  }

  @Test
  void commitConfirmadoConRespuestaInciertaSeRecuperaPorIdempotencia() throws Exception {
    org.mockito.Mockito.doAnswer(
            inv -> {
              var result = inv.callRealMethod();
              var e =
                  inv.getArgument(
                      0, com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria.class);
              if (e.accion()
                  == com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria
                      .DOCUMENTO_CARGADO)
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                          @Override
                          public void afterCommit() {
                            throw new org.springframework.dao.DataAccessResourceFailureException(
                                "Commit sintético confirmado sin respuesta");
                          }
                        });
              return result;
            })
        .when(auditoria)
        .registrar(org.mockito.ArgumentMatchers.any());
    var key = UUID.randomUUID();
    byte[] file = png();
    var a = cargar(key, file);
    assertThat(a.statusCode()).as(a.body()).isEqualTo(503);
    assertThat(fase(key)).isEqualTo("PUBLICADA");
    var b = cargar(key, file);
    assertThat(b.statusCode()).as(b.body()).isEqualTo(200);
    assertThat(documentos()).isEqualTo(1);
    assertThat(POSTS.get()).isEqualTo(1);
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id=:e AND"
                        + " accion='DOCUMENTO_CARGADO'")
                .param("e", escuela)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void mismoIdempotencyKeyConOtroContextoNoDuplica() throws Exception {
    var key = UUID.randomUUID();
    byte[] file = png();
    assertThat(cargar(key, file).statusCode()).isEqualTo(201);
    assertThat(
            cargarArchivo(
                    key, file, token, true, "sintetico.png", "image/png", "DNI_DEPORTISTA_DORSO")
                .statusCode())
        .isEqualTo(409);
    assertThat(documentos()).isEqualTo(1);
    assertThat(POSTS.get()).isEqualTo(1);
  }

  @Test
  void expiracionDuranteTransferenciaImpidePublicar() throws Exception {
    HOOK_STORAGE.set(() -> DESFASE.set(86400));
    var r = cargar(UUID.randomUUID(), png());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(401);
    assertThat(documentos()).isZero();
    assertThat(POSTS.get()).isEqualTo(1);
  }

  @Test
  void limiteRealDelServletNoPublicaNiEscanea() throws Exception {
    var r = cargar(UUID.randomUUID(), new byte[5000001]);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(413);
    assertThat(documentos()).isZero();
    assertThat(POSTS.get()).isZero();
    sinFugas(r);
  }

  /** PNG válido completado con un chunk auxiliar tEXt hasta medir exactamente {@code total} bytes. */
  static byte[] pngDeTamanio(int total) throws Exception {
    byte[] base = png();
    int iend = base.length - 12;
    byte[] contenido = new byte[total - base.length - 12];
    Arrays.fill(contenido, (byte) 'a');
    byte[] clave = "Comment\0".getBytes(StandardCharsets.ISO_8859_1);
    System.arraycopy(clave, 0, contenido, 0, clave.length);
    byte[] tipo = "tEXt".getBytes(StandardCharsets.US_ASCII);
    var crc = new java.util.zip.CRC32();
    crc.update(tipo);
    crc.update(contenido);
    var salida = new ByteArrayOutputStream();
    var datos = new DataOutputStream(salida);
    datos.write(base, 0, iend);
    datos.writeInt(contenido.length);
    datos.write(tipo);
    datos.write(contenido);
    datos.writeInt((int) crc.getValue());
    datos.write(base, iend, 12);
    return salida.toByteArray();
  }

  @Test
  void limitePorDefectoAdmiteCincoMegabytesYRechazaElByteSiguiente() throws Exception {
    byte[] limite = pngDeTamanio(5_000_000), excedido = pngDeTamanio(5_000_001);
    assertThat(limite).hasSize(5_000_000);
    assertThat(excedido).hasSize(5_000_001);
    var r = cargar(UUID.randomUUID(), excedido);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(413);
    assertThat(POSTS.get()).isZero();
    sinFugas(r);
    var a = cargar(UUID.randomUUID(), limite);
    assertThat(a.statusCode()).as(a.body()).isEqualTo(201);
    assertThat(POSTS.get()).isEqualTo(1);
    assertThat(documentos()).isEqualTo(1);
  }

  @Test
  void capacidadAcotadaRechazaSinEncolarOtroArchivo() throws Exception {
    var entraron = new CountDownLatch(2);
    var liberar = new CountDownLatch(1);
    HOOK_SCANNER.set(
        () -> {
          entraron.countDown();
          try {
            if (!liberar.await(6, TimeUnit.SECONDS)) throw new IllegalStateException();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException();
          }
        });
    byte[] file = png();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var a = pool.submit(() -> cargar(UUID.randomUUID(), file));
      var b = pool.submit(() -> cargar(UUID.randomUUID(), file));
      assertThat(entraron.await(5, TimeUnit.SECONDS)).isTrue();
      var c = cargar(UUID.randomUUID(), file);
      assertThat(c.statusCode()).as(c.body()).isEqualTo(429);
      liberar.countDown();
      assertThat(a.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
      assertThat(b.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
      assertThat(documentos()).isEqualTo(2);
    } finally {
      liberar.countDown();
    }
  }

  @Test
  void publicacionMantieneElBloqueoComunHastaConfirmarAuditoria() throws Exception {
    var publicando = new CountDownLatch(1);
    var liberar = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            inv -> {
              var result = inv.callRealMethod();
              var evento =
                  inv.getArgument(
                      0, com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria.class);
              if (evento.accion()
                  == com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria
                      .DOCUMENTO_CARGADO) {
                publicando.countDown();
                if (!liberar.await(5, TimeUnit.SECONDS)) throw new IllegalStateException();
              }
              return result;
            })
        .when(auditoria)
        .registrar(org.mockito.ArgumentMatchers.any());
    UUID admin = new DatosDb(jdbc).admin(escuela, "admin-concurrente@example.invalid", true);
    var identidad =
        new com.banfieldpatin.backend.seguridad.UsuarioAutenticado(admin, escuela, null, Rol.ADMIN);
    byte[] file = png();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var carga = pool.submit(() -> cargar(UUID.randomUUID(), file));
      assertThat(publicando.await(5, TimeUnit.SECONDS)).isTrue();
      var revocacion =
          pool.submit(
              () ->
                  vinculosAdmin.revocar(
                      identidad,
                      familia,
                      atleta,
                      com.banfieldpatin.backend.compartido.web.DatosSolicitud.NINGUNA));
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(1))
          .pollInterval(java.time.Duration.ofMillis(10))
          .until(
              () ->
                  jdbc.sql(
                              "SELECT count(*) FROM pg_stat_activity WHERE"
                                  + " datname=current_database() AND wait_event_type='Lock'")
                          .query(Integer.class)
                          .single()
                      > 0);
      assertThat(revocacion.isDone()).isFalse();
      liberar.countDown();
      assertThat(carga.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
      revocacion.get(10, TimeUnit.SECONDS);
      assertThat(documentos()).isEqualTo(1);
      assertThat(cargar(UUID.randomUUID(), file).statusCode()).isEqualTo(404);
    } finally {
      liberar.countDown();
    }
  }

  @Test
  void claveDeOperacionConOtroPropietarioNoSeReutiliza() throws Exception {
    UUID key = UUID.randomUUID();
    var datos = new DatosDb(jdbc);
    UUID tutor = datos.tutor(escuela, familia, "Tutor", "Sintético");
    jdbc.sql(
            "INSERT INTO"
                + " gestion_patin.operacion_carga_documental(escuela_id,solicitante_id,tutor_id,tipo,storage_bucket,objeto_id,idempotencia)"
                + " VALUES(:e,:u,:t,'OTRO','privado',:o,:k)")
        .param("e", escuela)
        .param("u", usuario)
        .param("t", tutor)
        .param("o", UUID.randomUUID())
        .param("k", key)
        .update();
    var r = cargar(key, png());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
    assertThat(POSTS.get()).isZero();
    assertThat(documentos()).isZero();
    assertThat(fase(key)).isEqualTo("RESERVADA");
  }
}
