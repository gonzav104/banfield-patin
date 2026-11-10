package com.banfieldpatin.backend.documentos.almacen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Verificación del adaptador REAL {@link SupabaseAlmacenDocumentos} contra un Supabase Storage local
 * y AISLADO (opt-in: no corre en las suites predeterminadas ni en CI). Contenido sintético y claves
 * generadas por el entorno local. Levantar, ejecutar y limpiar: ver "Verificación real de Supabase
 * Storage" en docs/DOCUMENTOS.md y backend/scripts/storage-local-u7b.sh.
 *
 * <p>Variables de entorno (las exporta {@code storage-local-u7b.sh env}): STORAGE_TEST_URL,
 * STORAGE_TEST_BUCKET, STORAGE_TEST_SERVICE_KEY, STORAGE_TEST_ANON_KEY, STORAGE_TEST_JWT_SECRET. Ninguna se registra ni se imprime.
 */
@Tag("supabase-real")
@ExtendWith(OutputCaptureExtension.class)
class SupabaseStorageRealTest {
  static final String URL = System.getenv("STORAGE_TEST_URL");
  static final String BUCKET = System.getenv("STORAGE_TEST_BUCKET");
  static final String SERVICIO = System.getenv("STORAGE_TEST_SERVICE_KEY");
  static final String ANON = System.getenv("STORAGE_TEST_ANON_KEY");
  static final String SECRETO = System.getenv("STORAGE_TEST_JWT_SECRET");
  static final HttpClient HTTP = HttpClient.newHttpClient();
  static final byte[] CONTENIDO = "contenido-sintetico-u7b".getBytes(StandardCharsets.UTF_8);

  final List<UUID> creados = new ArrayList<>();

  record Resp(int estado, String cuerpo, byte[] bytes) {}

  @BeforeAll
  static void entornoDisponible() throws Exception {
    // Falla (no se omite) si el entorno opt-in no está levantado: sería una falsa verificación.
    assertThat(URL).as("STORAGE_TEST_URL: ejecute  eval \"$(backend/scripts/storage-local-u7b.sh env)\"").isNotBlank();
    assertThat(List.of(BUCKET, SERVICIO, ANON, SECRETO)).doesNotContainNull();
    assertThat(http("GET", "/bucket/" + BUCKET, SERVICIO, null, null).estado()).isEqualTo(200);
  }

  // ------------------------------------------------------------------ utilidades

  static Resp http(String metodo, String ruta, String clave, String tipo, byte[] cuerpo)
      throws Exception {
    return http(URL, metodo, ruta, clave, tipo, cuerpo);
  }

  static Resp http(String base, String metodo, String ruta, String clave, String tipo, byte[] cuerpo)
      throws Exception {
    var b = HttpRequest.newBuilder(URI.create(base + "/storage/v1" + ruta)).timeout(Duration.ofSeconds(20));
    if (clave != null) b.header("Authorization", "Bearer " + clave).header("apikey", clave);
    if (tipo != null) b.header("Content-Type", tipo);
    b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(cuerpo));
    var r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    return new Resp(r.statusCode(), new String(r.body(), StandardCharsets.UTF_8), r.body());
  }

  static SupabaseAlmacenDocumentos adaptador(String base, String credencial, Duration respuesta) {
    return new SupabaseAlmacenDocumentos(
        new StoragePropiedades(base, BUCKET, credencial, Duration.ofSeconds(2), respuesta));
  }

  static SupabaseAlmacenDocumentos adaptador(String credencial) {
    return adaptador(URL, credencial, Duration.ofSeconds(5));
  }

  /** ¿Existe el objeto? Consulta directa con la clave de servicio, independiente del adaptador. */
  static boolean existe(UUID objeto) throws Exception {
    return http("GET", "/object/info/authenticated/" + BUCKET + "/" + objeto, SERVICIO, null, null)
            .estado() == 200;
  }

  DestinoPreparado destino() {
    UUID id = UUID.randomUUID();
    creados.add(id);
    return DestinoPreparado.desdeRegistro(BUCKET, id);
  }

  void crear(SupabaseAlmacenDocumentos a, DestinoPreparado d) {
    a.crear(d, new ByteArrayInputStream(CONTENIDO), CONTENIDO.length, "image/png");
  }

  @AfterEach
  void limpiar() throws Exception {
    try {
      http("PUT", "/bucket/" + BUCKET, SERVICIO, "application/json", "{\"public\":false}".getBytes());
      for (UUID id : creados)
        http("DELETE", "/object/" + BUCKET, SERVICIO, "application/json",
            ("{\"prefixes\":[\"" + id + "\"]}").getBytes());
    } finally {
      creados.clear();
    }
  }

  // ------------------------------------------------------------------ creación e inspección

  @Test
  void crearConfirmaElIdentificadorYGuardaExactamenteLosBytes() throws Exception {
    var a = adaptador(SERVICIO);
    var d = destino();
    crear(a, d);
    var leido = http("GET", "/object/authenticated/" + BUCKET + "/" + d.objetoIdInterno(), SERVICIO, null, null);
    assertThat(leido.estado()).isEqualTo(200);
    assertThat(leido.bytes()).isEqualTo(CONTENIDO);
  }

  @Test
  void inspeccionarUnObjetoExistenteConfirmaTamanioYTipo() throws Exception {
    var a = adaptador(SERVICIO);
    var d = destino();
    crear(a, d);
    var r = a.inspeccionar(d);
    assertThat(r.estado()).isEqualTo(InspeccionAlmacen.Estado.EXISTENCIA_CONFIRMADA);
    assertThat(r.tamanioBytes()).hasValue(CONTENIDO.length);
    assertThat(r.tipoContenido()).hasValue("image/png");
  }

  /**
   * U7c: el HEAD real de un objeto ausente devuelve 400 (no 404); el adaptador consulta entonces
   * /object/info y, con la credencial de servicio (BYPASSRLS) y la respuesta verificada, confirma la
   * ausencia.
   */
  @Test
  void unObjetoRealmenteAusenteSeConfirmaConLaCredencialDeServicio() throws Exception {
    var d = destino();
    assertThat(existe(d.objetoIdInterno())).isFalse();
    var r = adaptador(SERVICIO).inspeccionar(d);
    assertThat(r.estado()).isEqualTo(InspeccionAlmacen.Estado.AUSENCIA_OBSERVADA);
    var directo = http("HEAD", "/object/authenticated/" + BUCKET + "/" + d.objetoIdInterno(), SERVICIO, null, null);
    assertThat(directo.estado()).as("el HEAD real devuelve 400, no 404").isEqualTo(400);
  }

  @Test
  void unRolSinPermisoTambienVeNotFoundAsiQueUn404NuncaProbariaAusencia() throws Exception {
    var d = destino();
    crear(adaptador(SERVICIO), d);
    // El objeto EXISTE, pero para el rol anónimo el propio Storage lo informa como inexistente.
    var info = http("GET", "/object/info/authenticated/" + BUCKET + "/" + d.objetoIdInterno(), ANON, null, null);
    assertThat(info.cuerpo()).contains("\"statusCode\":\"404\"").contains("not_found");
    // El adaptador no lo toma por ausente: la lectura previa del bucket ya falla para ese rol.
    var r = adaptador(ANON).inspeccionar(d);
    assertThat(r.estado()).isEqualTo(InspeccionAlmacen.Estado.INCIERTA);
  }

  static String jwt(String rol) throws Exception {
    var b = java.util.Base64.getUrlEncoder().withoutPadding();
    String cab = b.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
    long ahora = System.currentTimeMillis() / 1000;
    String car = b.encodeToString(("{\"role\":\"" + rol + "\",\"sub\":\"00000000-0000-0000-0000-000000000001\",\"iat\":"
        + (ahora - 60) + ",\"exp\":" + (ahora + 3600) + "}").getBytes());
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(new javax.crypto.spec.SecretKeySpec(SECRETO.getBytes(), "HmacSHA256"));
    return cab + "." + car + "." + b.encodeToString(mac.doFinal((cab + "." + car).getBytes()));
  }

  /**
   * El caso peligroso: una credencial válida, no de servicio, que VE el bucket (supera la
   * verificación previa) pero a la que RLS le oculta los objetos. Storage le responde lo mismo, byte
   * a byte, para un objeto existente que la credencial de servicio para uno ausente. El adaptador
   * nunca lo toma por ausencia.
   */
  @Test
  void unaCredencialQueVeElBucketPeroNoLosObjetosNuncaConfirmaAusencia() throws Exception {
    String autenticado = jwt("authenticated");
    var existente = destino();
    crear(adaptador(SERVICIO), existente);
    var ausente = destino();

    assertThat(http("GET", "/bucket/" + BUCKET, autenticado, null, null).estado())
        .as("ve el bucket: la verificación previa no lo detiene").isEqualTo(200);
    var ocultoPorRls = http("GET", "/object/info/authenticated/" + BUCKET + "/" + existente.objetoIdInterno(), autenticado, null, null);
    var ausenteReal = http("GET", "/object/info/authenticated/" + BUCKET + "/" + ausente.objetoIdInterno(), SERVICIO, null, null);
    assertThat(ocultoPorRls.estado()).isEqualTo(ausenteReal.estado()).isEqualTo(400);
    assertThat(ocultoPorRls.cuerpo()).as("respuestas indistinguibles").isEqualTo(ausenteReal.cuerpo());

    var a = adaptador(autenticado);
    assertThat(a.inspeccionar(existente).estado()).isEqualTo(InspeccionAlmacen.Estado.INCIERTA);
    assertThat(a.inspeccionar(ausente).estado()).isEqualTo(InspeccionAlmacen.Estado.INCIERTA);
    assertThat(adaptador(SERVICIO).inspeccionar(existente).estado())
        .isEqualTo(InspeccionAlmacen.Estado.EXISTENCIA_CONFIRMADA);
    assertThat(adaptador(SERVICIO).inspeccionar(ausente).estado())
        .isEqualTo(InspeccionAlmacen.Estado.AUSENCIA_OBSERVADA);
  }

  @Test
  void unBucketInexistenteNuncaEsAusenciaDelObjeto() {
    var otro = new SupabaseAlmacenDocumentos(
        new StoragePropiedades(URL, "banfield-no-existe", SERVICIO, Duration.ofSeconds(2), Duration.ofSeconds(5)));
    var r = otro.inspeccionar(DestinoPreparado.desdeRegistro("banfield-no-existe", UUID.randomUUID()));
    assertThat(r.estado()).isEqualTo(InspeccionAlmacen.Estado.INCIERTA);
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "400|{\"statusCode\":\"404\",\"error\":\"not_found\",\"message\":\"Object not found\"}",
    "400|{\"statusCode\":\"404\",\"error\":\"Bucket not found\",\"message\":\"Bucket not found\",\"code\":\"NoSuchBucket\"}",
    "400|{\"statusCode\":\"403\",\"error\":\"Unauthorized\",\"message\":\"x\",\"code\":\"AccessDenied\"}",
    "404|{\"message\":\"no Route matched with those values\"}",
    "404|{\"statusCode\":\"404\",\"error\":\"not_found\",\"message\":\"Object not found\",\"code\":\"NoSuchKey\"}",
    "400|no-es-json",
    "502|{\"message\":\"An invalid response was received from the upstream server\"}"
  })
  void unaRespuestaDeInfoNoVerificadaEsIncertidumbre(String caso) throws Exception {
    var partes = caso.split("\\|", 2);
    try (var proxy = new ProxyDeFallos()) {
      var a = adaptador(proxy.url(), SERVICIO, Duration.ofSeconds(5));
      var d = destino();
      assertThat(a.inspeccionar(d).estado()).as("control: sin alterar, ausencia confirmada")
          .isEqualTo(InspeccionAlmacen.Estado.AUSENCIA_OBSERVADA);
      proxy.infoEstado = Integer.parseInt(partes[0]);
      proxy.infoCuerpo = partes[1];
      assertThat(a.inspeccionar(d).estado()).isEqualTo(InspeccionAlmacen.Estado.INCIERTA);
    }
  }

  @Test
  void unaConsultaDeInfoMasLentaQueElPlazoEsTimeoutNuncaAusencia() throws Exception {
    try (var proxy = new ProxyDeFallos()) {
      var a = adaptador(proxy.url(), SERVICIO, Duration.ofSeconds(2));
      proxy.infoLenta = true;
      var r = a.inspeccionar(destino());
      assertThat(r.estado()).isEqualTo(InspeccionAlmacen.Estado.INCIERTA);
      assertThat(r.motivo()).hasValue(FalloAlmacenDocumentos.Codigo.TIMEOUT);
    }
  }

  /**
   * Una ausencia confirmada es una observación, no el fin de un escritor: con un POST todavía
   * retenido en camino, el objeto está ausente; al liberarlo, Storage lo crea. Por eso U5 cierra sin
   * publicar y el objeto tardío queda huérfano.
   */
  @Test
  void unaAusenciaConfirmadaNoPruebaElFinDeUnPostEnCurso() throws Exception {
    try (var proxy = new ProxyDeFallos(); var tareas = Executors.newVirtualThreadPerTaskExecutor()) {
      var a = adaptador(proxy.url(), SERVICIO, Duration.ofSeconds(8));
      var d = destino();
      proxy.retenerPost = new java.util.concurrent.CountDownLatch(1);
      var envio = tareas.submit(() -> { crear(a, d); return null; });
      long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (proxy.postsRetenidos.get() == 0 && System.nanoTime() < fin) Thread.sleep(20);
      assertThat(proxy.postsRetenidos.get()).isEqualTo(1);
      assertThat(adaptador(SERVICIO).inspeccionar(d).estado()).isEqualTo(InspeccionAlmacen.Estado.AUSENCIA_OBSERVADA);
      proxy.retenerPost.countDown();
      envio.get(10, TimeUnit.SECONDS);
      assertThat(adaptador(SERVICIO).inspeccionar(d).estado()).isEqualTo(InspeccionAlmacen.Estado.EXISTENCIA_CONFIRMADA);
    }
  }

  // ------------------------------------------------------------------ sobrescritura

  /** Hallazgo menor: el duplicado llega como HTTP 400 (con 409 en el cuerpo), no como CONFLICTO. */
  @Test
  void unDuplicadoNuncaSobrescribeAunqueSeClasifiqueComoRechazado() throws Exception {
    var a = adaptador(SERVICIO);
    var d = destino();
    crear(a, d);
    assertThatThrownBy(
            () -> a.crear(d, new ByteArrayInputStream("OTRO-CONTENIDO!".getBytes()), 15, "image/png"))
        .isInstanceOfSatisfying(
            FalloAlmacenDocumentos.class,
            e -> {
              assertThat(e.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.RECHAZADO);
              assertThat(e.resultadoIncierto()).isFalse();
            });
    var leido = http("GET", "/object/authenticated/" + BUCKET + "/" + d.objetoIdInterno(), SERVICIO, null, null);
    assertThat(leido.bytes()).as("el original no se modificó").isEqualTo(CONTENIDO);
    var directo = http("POST", "/object/" + BUCKET + "/" + d.objetoIdInterno(), SERVICIO, "image/png", "x".getBytes());
    assertThat(directo.cuerpo()).contains("\"statusCode\":\"409\"");
  }

  // ------------------------------------------------------------------ credenciales y permisos

  private static String token(String secreto, long exp) throws Exception {
    var b = java.util.Base64.getUrlEncoder().withoutPadding();
    String cab = b.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
    long ahora = System.currentTimeMillis() / 1000;
    String car = b.encodeToString(("{\"role\":\"service_role\",\"iat\":" + (ahora - 100) + ",\"exp\":" + exp + "}").getBytes());
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(new javax.crypto.spec.SecretKeySpec(secreto.getBytes(), "HmacSHA256"));
    return cab + "." + car + "." + b.encodeToString(mac.doFinal((cab + "." + car).getBytes()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"basura", "firma-ajena", "vencido", "anonimo"})
  void unaCredencialInvalidaONoAutorizadaNuncaSeTomaPorAusenciaNiCreaObjetos(String caso, CapturedOutput salida)
      throws Exception {
    long ahora = System.currentTimeMillis() / 1000;
    String credencial =
        switch (caso) {
          case "basura" -> "no.es.un.jwt";
          case "firma-ajena" -> token("otro-secreto-distinto-0123456789abcdef", ahora + 3600);
          case "vencido" -> token(SECRETO, ahora - 10);
          default -> ANON;
        };
    var existente = destino();
    crear(adaptador(SERVICIO), existente);
    var intruso = adaptador(credencial);

    var visto = intruso.inspeccionar(existente);
    assertThat(visto.estado()).isEqualTo(InspeccionAlmacen.Estado.INCIERTA);
    assertThat(visto.motivo()).isPresent();

    var nuevo = destino();
    assertThatThrownBy(() -> crear(intruso, nuevo))
        .isInstanceOfSatisfying(
            FalloAlmacenDocumentos.class,
            e -> {
              assertThat(e.codigo())
                  .isIn(FalloAlmacenDocumentos.Codigo.AUTENTICACION, FalloAlmacenDocumentos.Codigo.RECHAZADO);
              assertThat(e.resultadoIncierto()).as("falló antes de transferir").isFalse();
            });
    assertThat(existe(nuevo.objetoIdInterno())).as("no se creó ningún objeto").isFalse();
    assertThat(salida.getAll())
        .doesNotContain(SERVICIO)
        .doesNotContain(ANON)
        .doesNotContain(SECRETO)
        .doesNotContain(credencial);
  }

  @Test
  void unBucketPublicoSeRechazaAntesDeTransferir() throws Exception {
    assertThat(http("PUT", "/bucket/" + BUCKET, SERVICIO, "application/json", "{\"public\":true}".getBytes()).estado())
        .isEqualTo(200);
    var a = adaptador(SERVICIO);
    var d = destino();
    assertThatThrownBy(() -> crear(a, d))
        .isInstanceOfSatisfying(
            FalloAlmacenDocumentos.class,
            e -> {
              assertThat(e.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.RECHAZADO);
              assertThat(e.resultadoIncierto()).isFalse();
            });
    assertThat(existe(d.objetoIdInterno())).isFalse();
    assertThat(a.inspeccionar(d).estado()).isEqualTo(InspeccionAlmacen.Estado.INCIERTA);
  }

  // ------------------------------------------------------------------ indisponibilidad y timeouts

  /**
   * Proxy local de fallos delante del Storage real: reenvía todo al Storage y, según el modo,
   * simula el comportamiento de la pasarela ante una caída (502), una respuesta más lenta que el
   * plazo del cliente, o una respuesta tardía solo del POST (el objeto SÍ se crea). Es determinista
   * y no detiene ni reinicia nada de la pila compartida.
   */
  static final class ProxyDeFallos implements AutoCloseable {
    enum Modo { NORMAL, CAIDO_502, LENTO, POST_TARDIO }

    final HttpServer servidor;
    volatile Modo modo = Modo.NORMAL;
    volatile Integer infoEstado;
    volatile String infoCuerpo;
    volatile boolean infoLenta;
    volatile java.util.concurrent.CountDownLatch retenerPost;
    final java.util.concurrent.atomic.AtomicInteger postsRetenidos = new java.util.concurrent.atomic.AtomicInteger();

    ProxyDeFallos() throws Exception {
      servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      var restringidas = Set.of("host", "content-length", "connection", "expect", "transfer-encoding");
      servidor.createContext(
          "/",
          x -> {
            try {
              byte[] cuerpo = x.getRequestBody().readAllBytes();
              if (modo == Modo.CAIDO_502) {
                byte[] b = "{\"message\":\"An invalid response was received from the upstream server\"}".getBytes();
                x.getResponseHeaders().set("Content-Type", "application/json");
                x.sendResponseHeaders(502, b.length);
                x.getResponseBody().write(b);
                return;
              }
              boolean info = x.getRequestURI().getPath().contains("/object/info/");
              if (info && infoLenta) Thread.sleep(3500);
              if (info && infoEstado != null) {
                byte[] b = infoCuerpo.getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().set("Content-Type", "application/json");
                x.sendResponseHeaders(infoEstado, b.length);
                x.getResponseBody().write(b);
                return;
              }
              var retencion = retenerPost;
              if (retencion != null && x.getRequestMethod().equals("POST")) {
                postsRetenidos.incrementAndGet();
                retencion.await(15, TimeUnit.SECONDS);
              }
              var p = HttpRequest.newBuilder(URI.create(URL + x.getRequestURI()))
                  .method(x.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(cuerpo));
              x.getRequestHeaders().forEach((k, v) -> {
                if (!restringidas.contains(k.toLowerCase())) v.forEach(valor -> p.header(k, valor));
              });
              var r = HTTP.send(p.build(), HttpResponse.BodyHandlers.ofByteArray());
              boolean post = x.getRequestMethod().equals("POST") && x.getRequestURI().getPath().contains("/object/");
              if (modo == Modo.LENTO || modo == Modo.POST_TARDIO && post) Thread.sleep(3500);
              r.headers().firstValue("content-type").ifPresent(t -> x.getResponseHeaders().set("Content-Type", t));
              if (x.getRequestMethod().equals("HEAD"))
                // El HEAD no lleva cuerpo, pero conserva el Content-Length del recurso.
                r.headers().firstValue("content-length").ifPresent(l -> x.getResponseHeaders().set("Content-Length", l));
              x.sendResponseHeaders(r.statusCode(), r.body().length == 0 ? -1 : r.body().length);
              if (r.body().length > 0) x.getResponseBody().write(r.body());
            } catch (Exception ex) {
              /* el cliente ya abandonó */
            } finally {
              x.close();
            }
          });
      servidor.start();
    }

    String url() { return "http://127.0.0.1:" + servidor.getAddress().getPort(); }

    @Override
    public void close() { servidor.stop(0); }
  }

  @Test
  void conStorageCaidoTodoEsIncertidumbreNuncaAusenciaYSeRecupera(CapturedOutput salida) throws Exception {
    try (var proxy = new ProxyDeFallos()) {
      var a = adaptador(proxy.url(), SERVICIO, Duration.ofSeconds(5));
      var d = destino();
      proxy.modo = ProxyDeFallos.Modo.CAIDO_502;
      var r = a.inspeccionar(d);
      assertThat(r.estado()).isEqualTo(InspeccionAlmacen.Estado.INCIERTA);
      assertThat(r.motivo()).hasValue(FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA);
      assertThatThrownBy(() -> crear(a, d))
          .isInstanceOfSatisfying(
              FalloAlmacenDocumentos.class,
              e -> {
                assertThat(e.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA);
                assertThat(e.resultadoIncierto()).as("la lectura previa falló: no hubo POST").isFalse();
              });
      assertThat(existe(d.objetoIdInterno())).isFalse();
      proxy.modo = ProxyDeFallos.Modo.NORMAL;
      assertThatCode(() -> crear(a, d)).doesNotThrowAnyException();
      assertThat(a.inspeccionar(d).estado()).isEqualTo(InspeccionAlmacen.Estado.EXISTENCIA_CONFIRMADA);
    }
    assertThat(salida.getAll()).doesNotContain(SERVICIO).doesNotContain(ANON);
  }

  @Test
  void sinNadieEscuchandoEsErrorDeRedNuncaAusencia() throws Exception {
    int puertoCerrado;
    try (var s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
      puertoCerrado = s.getLocalPort();
    }
    var a = adaptador("http://127.0.0.1:" + puertoCerrado, SERVICIO, Duration.ofSeconds(5));
    var d = destino();
    var r = a.inspeccionar(d);
    assertThat(r.estado()).isEqualTo(InspeccionAlmacen.Estado.INCIERTA);
    assertThat(r.motivo()).hasValue(FalloAlmacenDocumentos.Codigo.RED);
    assertThatThrownBy(() -> crear(a, d))
        .isInstanceOfSatisfying(
            FalloAlmacenDocumentos.class, e -> assertThat(e.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.RED));
  }

  @Test
  void conUnaRespuestaMasLentaQueElPlazoSeInformaTimeoutNuncaAusencia() throws Exception {
    try (var proxy = new ProxyDeFallos()) {
      var a = adaptador(proxy.url(), SERVICIO, Duration.ofSeconds(2));
      var d = destino();
      proxy.modo = ProxyDeFallos.Modo.LENTO;
      long inicio = System.nanoTime();
      var r = a.inspeccionar(d);
      assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(8));
      assertThat(r.estado()).isEqualTo(InspeccionAlmacen.Estado.INCIERTA);
      assertThat(r.motivo()).hasValue(FalloAlmacenDocumentos.Codigo.TIMEOUT);
      assertThatThrownBy(() -> crear(a, d))
          .isInstanceOfSatisfying(
              FalloAlmacenDocumentos.class,
              e -> assertThat(e.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.TIMEOUT));
      assertThat(existe(d.objetoIdInterno())).isFalse();
    }
  }

  /**
   * Respuesta tardía real: el POST llega y Storage crea el objeto, pero la respuesta se demora más
   * que el plazo del cliente. El adaptador informa TIMEOUT con resultado incierto y, después, el
   * objeto efectivamente existe: el timeout no prueba ausencia.
   */
  @Test
  void unaRespuestaTardiaDelPostEsIncertaYElObjetoIgualQuedaCreado(CapturedOutput salida) throws Exception {
    try (var proxy = new ProxyDeFallos()) {
      var a = adaptador(proxy.url(), SERVICIO, Duration.ofSeconds(2));
      var d = destino();
      proxy.modo = ProxyDeFallos.Modo.POST_TARDIO;
      assertThatThrownBy(() -> crear(a, d))
          .isInstanceOfSatisfying(
              FalloAlmacenDocumentos.class,
              e -> {
                assertThat(e.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.TIMEOUT);
                assertThat(e.resultadoIncierto()).isTrue();
              });
      long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!existe(d.objetoIdInterno()) && System.nanoTime() < fin) Thread.sleep(100);
      assertThat(existe(d.objetoIdInterno())).as("el objeto quedó creado pese al timeout").isTrue();
      assertThat(adaptador(SERVICIO).inspeccionar(d).estado())
          .isEqualTo(InspeccionAlmacen.Estado.EXISTENCIA_CONFIRMADA);
    }
    assertThat(salida.getAll()).doesNotContain(SERVICIO).doesNotContain(new String(CONTENIDO));
  }

  // ------------------------------------------------------------------ compensación

  @Test
  void eliminarUnObjetoRecienCreadoUsaElContratoRealYSeConsumeElRecibo() throws Exception {
    var a = adaptador(SERVICIO);
    var creado = a.crear(CONTENIDO, "image/png");
    creados.add(creado.claveInterna());
    assertThat(existe(creado.claveInterna())).isTrue();
    a.eliminarCreado(creado);
    assertThat(existe(creado.claveInterna())).isFalse();
    assertThatThrownBy(() -> a.eliminarCreado(creado))
        .isInstanceOfSatisfying(
            FalloAlmacenDocumentos.class,
            e -> assertThat(e.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.ENTRADA_INVALIDA));
  }
}
