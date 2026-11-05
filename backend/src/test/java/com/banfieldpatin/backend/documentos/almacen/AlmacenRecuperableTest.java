package com.banfieldpatin.backend.documentos.almacen;

import static org.assertj.core.api.Assertions.*;
import static com.banfieldpatin.backend.documentos.almacen.InspeccionAlmacen.Estado.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class AlmacenRecuperableTest {
    private HttpServer servidor;
    private ExecutorService hilos;
    private final List<String> solicitudes = new CopyOnWriteArrayList<>();
    private volatile int estadoBucket = 200, estadoPost = 200, estadoHead = 200;
    private volatile String bucket = "{\"public\":false}", longitud = "3", mime = "application/pdf";
    private volatile String respuestaPost;
    private volatile boolean perderConfirmacion, demorarHead, bloquearPost;
    private final CountDownLatch recibido = new CountDownLatch(1), liberar = new CountDownLatch(1);
    private final AtomicLong bytes = new AtomicLong();
    private volatile String upsert, autorizacion;

    @BeforeEach void iniciar() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hilos = Executors.newVirtualThreadPerTaskExecutor(); servidor.setExecutor(hilos);
        servidor.createContext("/", x -> {
            try (x) {
                String ruta = x.getRequestURI().getPath(), metodo = x.getRequestMethod();
                solicitudes.add(metodo + " " + ruta);
                if (ruta.startsWith("/storage/v1/bucket/")) {
                    byte[] b = bucket.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    x.sendResponseHeaders(estadoBucket, b.length); x.getResponseBody().write(b); return;
                }
                if (metodo.equals("HEAD")) {
                    if (demorarHead) { liberar.await(3, TimeUnit.SECONDS); return; }
                    if (longitud != null) x.getResponseHeaders().set("Content-Length", longitud);
                    if (mime != null) x.getResponseHeaders().set("Content-Type", mime);
                    x.getResponseHeaders().set("ETag", "\"" + "a".repeat(64) + "\"");
                    x.getResponseHeaders().set("Location", "/objeto-no-autorizado");
                    x.sendResponseHeaders(estadoHead, -1); return;
                }
                assertThat(metodo).isEqualTo("POST");
                upsert = x.getRequestHeaders().getFirst("x-upsert");
                autorizacion = x.getRequestHeaders().getFirst("Authorization");
                bytes.set(x.getRequestBody().transferTo(OutputStream.nullOutputStream())); recibido.countDown();
                if (bloquearPost) liberar.await(3, TimeUnit.SECONDS);
                if (perderConfirmacion) return;
                String clave = ruta.substring(ruta.lastIndexOf('/') + 1);
                byte[] b = (respuestaPost == null ? "{\"Key\":\"privado/" + clave + "\"}" : respuestaPost).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                x.sendResponseHeaders(estadoPost, b.length); x.getResponseBody().write(b);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }); servidor.start();
    }
    @AfterEach void cerrar() { liberar.countDown(); if (servidor != null) servidor.stop(0); if (hilos != null) hilos.close(); }
    private SupabaseAlmacenDocumentos almacen() {
        return new SupabaseAlmacenDocumentos(new StoragePropiedades("http://127.0.0.1:" + servidor.getAddress().getPort(),
                "privado", "credencial-sintetica", Duration.ofSeconds(1), Duration.ofMillis(500)));
    }
    private InputStream contenido() { return new ByteArrayInputStream(new byte[]{1,2,3}); }
    private long posts() { return solicitudes.stream().filter(s -> s.startsWith("POST ")).count(); }
    private void crear(SupabaseAlmacenDocumentos a, DestinoPreparado d) { a.crear(d, contenido(), 3, "application/pdf"); }

    @Test void preparaSinTraficoYRecuperaExactamenteTrasReinicio() {
        var a = almacen(); var d = a.prepararDestino();
        assertThat(solicitudes).isEmpty(); assertThat(d.bucketInterno()).isEqualTo("privado");
        var restaurado = DestinoPreparado.desdeRegistro(d.bucketInterno(), d.objetoIdInterno());
        var nuevo = almacen(); crear(nuevo, restaurado);
        assertThat(solicitudes).containsExactly("GET /storage/v1/bucket/privado", "POST /storage/v1/object/privado/" + d.objetoIdInterno());
        assertThat(upsert).isEqualTo("false"); assertThat(autorizacion).isEqualTo("Bearer credencial-sintetica"); assertThat(bytes.get()).isEqualTo(3);
        assertThat(d.toString()).doesNotContain(d.objetoIdInterno().toString(), "privado/");
        assertThat(a.prepararDestino().objetoIdInterno()).isNotEqualTo(d.objetoIdInterno());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"../privado", "a/b", "%2e%2e", "a?b", "a#b", "á", " a", "a\\b"})
    void destinoRechazaRutasSinTrafico(String b) {
        assertThatThrownBy(() -> DestinoPreparado.desdeRegistro(b, UUID.randomUUID())).isInstanceOf(FalloAlmacenDocumentos.class);
        assertThat(solicitudes).isEmpty();
    }
    @Test void destinoAjenoONuloNoConsumeContenidoNiHaceTrafico() {
        var a = almacen();
        assertThatThrownBy(() -> crear(a, DestinoPreparado.desdeRegistro("otro", UUID.randomUUID()))).isInstanceOf(FalloAlmacenDocumentos.class);
        assertThatThrownBy(() -> a.inspeccionar(DestinoPreparado.desdeRegistro("otro", UUID.randomUUID()))).isInstanceOf(FalloAlmacenDocumentos.class);
        assertThatThrownBy(() -> crear(a, null)).isInstanceOf(FalloAlmacenDocumentos.class);
        assertThatThrownBy(() -> DestinoPreparado.desdeRegistro("privado", null)).isInstanceOf(FalloAlmacenDocumentos.class);
        assertThat(solicitudes).isEmpty();
    }
    @ParameterizedTest @ValueSource(ints={401,403,409,429,500,302})
    void falloDeAltaSaneadoSinReintento(int estado) {
        estadoPost=estado; respuestaPost="dato-sensible-sintetico"; var a=almacen();
        assertThatThrownBy(() -> crear(a,a.prepararDestino())).isInstanceOfSatisfying(FalloAlmacenDocumentos.class,e->{
            assertThat(e.getCause()).isNull(); assertThat(e.toString()).doesNotContain("dato-sensible", "credencial-sintetica", "127.0.0.1");
            assertThat(e.resultadoIncierto()).isEqualTo(estado>=500 || estado==302);
            if(estado==409) assertThat(e.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.CONFLICTO);
        }); assertThat(posts()).isEqualTo(1);
    }
    @Test void colisionNoSobrescribeNiCompensaAutomaticamente() {
        var a=almacen();
        var d=a.prepararDestino();crear(a,d);estadoPost=409;
        assertThatThrownBy(()->crear(a,d)).isInstanceOfSatisfying(FalloAlmacenDocumentos.class,e->assertThat(e.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.CONFLICTO));
        assertThat(posts()).isEqualTo(2);
        assertThat(upsert).isEqualTo("false");
        assertThat(solicitudes).noneMatch(s->s.startsWith("DELETE "));
    }
    @ParameterizedTest @ValueSource(strings={"", "{}", "[]", "{\"Key\":\"otro/objeto\"}", "null", "no-json"})
    void confirmacionInvalidaDejaAltaIncierta(String cuerpo) {
        respuestaPost=cuerpo;
        var a=almacen();
        assertThatThrownBy(()->crear(a,a.prepararDestino())).isInstanceOfSatisfying(FalloAlmacenDocumentos.class,e->assertThat(e.resultadoIncierto()).isTrue());
        assertThat(posts()).isEqualTo(1);
    }
    @Test void confirmacionPerdidaSeInspeccionaConReferenciaRecuperadaSinBorrar() {
        perderConfirmacion=true;
        var a=almacen();
        var d=a.prepararDestino();
        assertThatThrownBy(()->crear(a,d)).isInstanceOfSatisfying(FalloAlmacenDocumentos.class,e->assertThat(e.resultadoIncierto()).isTrue());
        var inspeccion=almacen().inspeccionar(DestinoPreparado.desdeRegistro(d.bucketInterno(),d.objetoIdInterno()));
        assertThat(inspeccion.estado()).isEqualTo(EXISTENCIA_CONFIRMADA);
        assertThat(posts()).isEqualTo(1);
        assertThat(solicitudes).noneMatch(s->s.startsWith("DELETE "));
    }
    @Test void inspeccionHeadNoDescargaNiEntregaEtagComoSha() {
        var a=almacen();
        var d=a.prepararDestino();
        var i=a.inspeccionar(d);
        assertThat(i.estado()).isEqualTo(EXISTENCIA_CONFIRMADA);
        assertThat(i.tamanioBytes()).hasValue(3);
        assertThat(i.tipoContenido()).contains("application/pdf");
        assertThat(i.motivo()).isEmpty();
        assertThat(solicitudes).containsExactly("GET /storage/v1/bucket/privado", "HEAD /storage/v1/object/authenticated/privado/"+d.objetoIdInterno());
        assertThat(i.toString()).doesNotContain("a".repeat(64),d.objetoIdInterno().toString());
    }
    @ParameterizedTest @ValueSource(ints={401,403,400,429,500,503,302})
    void inspeccionInciertaYTipadaAnteFallos(int estado) {
        estadoHead=estado;
        var a=almacen();
        var i=a.inspeccionar(a.prepararDestino());
        assertThat(i.estado()).isEqualTo(INCIERTA);
        assertThat(i.motivo()).isPresent();
        assertThat(i.tamanioBytes()).isEmpty();
        assertThat(i.tipoContenido()).isEmpty();
        assertThat(posts()).isZero();
        assertThat(solicitudes).hasSize(2);
    }
    @ParameterizedTest @CsvSource(value={"-1,application/pdf", "0,application/pdf", "abc,application/pdf", "9223372036854775808,application/pdf", "3,bad mime", "3,application/pdf;name=persona", "3,*/*"})
    void metadatosInesperadosNoConfirmanExistencia(String l,String m) {
        longitud=l;mime=m;
        var a=almacen();
        var i=a.inspeccionar(a.prepararDestino());
        assertThat(i.estado()).isEqualTo(INCIERTA);
        assertThat(i.motivo()).isPresent();
    }
    @Test void metadatosAusentesNoConfirmanExistencia() {
        longitud=null;mime=null;
        var a=almacen();
        assertThat(a.inspeccionar(a.prepararDestino()).estado()).isEqualTo(INCIERTA);
    }
    @Test void un404NoImpideQueUnPostPendienteTermineDespues() throws Exception {
        bloquearPost=true;estadoHead=404;
        var a=almacen();
        var d=a.prepararDestino();
        try(var tareas=Executors.newVirtualThreadPerTaskExecutor()) {
            var futuro=tareas.submit(()->crear(a,d));
        assertThat(recibido.await(2,TimeUnit.SECONDS)).isTrue();
            var i=almacen().inspeccionar(DestinoPreparado.desdeRegistro(d.bucketInterno(),d.objetoIdInterno()));
            assertThat(i.estado()).isEqualTo(AUSENCIA_OBSERVADA);
        assertThat(i.tamanioBytes()).isEmpty();
        assertThat(i.motivo()).isEmpty();
            estadoHead=200;liberar.countDown();futuro.get(2,TimeUnit.SECONDS);
        assertThat(a.inspeccionar(d).estado()).isEqualTo(EXISTENCIA_CONFIRMADA);
        } assertThat(posts()).isEqualTo(1);
    }
    @Test void timeoutDeInspeccionEsIncertidumbreAcotada() {
        demorarHead=true;
        var a=almacen();long inicio=System.nanoTime();
        var i=a.inspeccionar(a.prepararDestino());
        assertThat(i.estado()).isEqualTo(INCIERTA);
        assertThat(i.motivo()).contains(FalloAlmacenDocumentos.Codigo.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime()-inicio)).isLessThan(Duration.ofSeconds(3));
    }
    @Test void bucketNoPrivadoBloqueaAltaEInspeccion() {
        bucket="{\"public\":true}";
        var a=almacen();
        var d=a.prepararDestino();
        assertThatThrownBy(()->crear(a,d)).isInstanceOf(FalloAlmacenDocumentos.class);
        assertThat(a.inspeccionar(d).estado()).isEqualTo(INCIERTA);
        assertThat(posts()).isZero();
        assertThat(solicitudes).allMatch(s->s.startsWith("GET /storage/v1/bucket/"));
    }
    @Test void falloAntesDeTransferirNoLeeElStream() {
        estadoBucket=503;
        var a=almacen();
        var lecturas=new AtomicInteger();
        var stream=new InputStream(){public int read(){lecturas.incrementAndGet();return 1;}};
        assertThatThrownBy(()->a.crear(a.prepararDestino(),stream,3,"application/pdf")).isInstanceOfSatisfying(FalloAlmacenDocumentos.class,e->assertThat(e.resultadoIncierto()).isFalse());
        assertThat(lecturas).hasValue(0);
        assertThat(posts()).isZero();
    }
    @Test void copiaAcotadaSinBufferCompletoYLongitudExacta() {
        var a=almacen();
        var max=new AtomicInteger();long total=2_000_000;
        var stream=new InputStream(){long restantes=total;public int read(){return restantes-->0?1:-1;}public int read(byte[]b,int o,int l){max.accumulateAndGet(l,Math::max);if(restantes<=0)return -1;int n=(int)Math.min(l,restantes);Arrays.fill(b,o,o+n,(byte)1);restantes-=n;return n;}};
        a.crear(a.prepararDestino(),stream,total,"application/pdf");
        assertThat(bytes.get()).isEqualTo(total);
        assertThat(max.get()).isLessThanOrEqualTo(8192);
        assertThat(posts()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(ints={2,4})
    void longitudRealDistintaDeDeclaradaNoSeConfirma(int real) {
        var a=almacen();
        assertThatThrownBy(()->a.crear(a.prepararDestino(),new ByteArrayInputStream(new byte[real]),3,"application/pdf")).isInstanceOfSatisfying(FalloAlmacenDocumentos.class,e->assertThat(e.resultadoIncierto()).isTrue());
        assertThat(posts()).isLessThanOrEqualTo(1);
    }
    @Test void errorDeLecturaDuranteTransferenciaEsSaneado() {
        var a=almacen();
        var stream=new InputStream(){public int read()throws IOException{throw new IOException("dato-sensible-sintetico");}};
        assertThatThrownBy(()->a.crear(a.prepararDestino(),stream,3,"application/pdf")).isInstanceOfSatisfying(FalloAlmacenDocumentos.class,e->{assertThat(e.resultadoIncierto()).isTrue();
        assertThat(e.getCause()).isNull();
        assertThat(e.toString()).doesNotContain("dato-sensible");});
        assertThat(posts()).isLessThanOrEqualTo(1);
    }
    @Test void entradaInvalidaNoHaceTrafico() {
        var a=almacen();
        var d=a.prepararDestino();
        assertThatThrownBy(()->a.crear(d,null,3,"application/pdf")).isInstanceOf(FalloAlmacenDocumentos.class);
        assertThatThrownBy(()->a.crear(d,contenido(),0,"application/pdf")).isInstanceOf(FalloAlmacenDocumentos.class);
        assertThatThrownBy(()->a.crear(d,contenido(),-1,"application/pdf")).isInstanceOf(FalloAlmacenDocumentos.class);
        assertThatThrownBy(()->a.crear(d,contenido(),3,null)).isInstanceOf(FalloAlmacenDocumentos.class);
        assertThat(solicitudes).isEmpty();
    }
    @Test void redCaidaAntesDeInspeccionarNoSeConvierteEnAusencia() {
        var a=almacen();
        var d=a.prepararDestino();servidor.stop(0);
        assertThat(a.inspeccionar(d).estado()).isEqualTo(INCIERTA);
    }
    @Test void timeoutTrasRecibirPostNoConfirmaNiCompensa() throws Exception {
        bloquearPost = true;
        var a = almacen();
        assertThatThrownBy(() -> crear(a, a.prepararDestino()))
                .isInstanceOfSatisfying(FalloAlmacenDocumentos.class, e -> {
                    assertThat(e.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.TIMEOUT);
                    assertThat(e.resultadoIncierto()).isTrue();
                });
        assertThat(recibido.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(posts()).isEqualTo(1);
        assertThat(solicitudes).noneMatch(solicitud -> solicitud.startsWith("DELETE "));
    }

    @ParameterizedTest @ValueSource(ints = {401, 403, 404, 500})
    void falloDeConsultaDelBucketNuncaEsAusenciaDeObjeto(int estado) {
        estadoBucket = estado;
        var a = almacen();
        var i = a.inspeccionar(a.prepararDestino());
        assertThat(i.estado()).isEqualTo(INCIERTA);
        assertThat(i.motivo()).isPresent();
        assertThat(solicitudes).containsExactly("GET /storage/v1/bucket/privado");
    }

    @Test void contenidoPrestadoNoSeReabreNiCierraImplicitamente() throws Exception {
        var cerrado = new AtomicBoolean();
        var stream = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override public void close() throws IOException { cerrado.set(true); super.close(); }
        };
        var a = almacen();
        a.crear(a.prepararDestino(), stream, 3, "application/pdf");
        assertThat(cerrado).isFalse();
        stream.close();
        assertThat(cerrado).isTrue();
    }

}
