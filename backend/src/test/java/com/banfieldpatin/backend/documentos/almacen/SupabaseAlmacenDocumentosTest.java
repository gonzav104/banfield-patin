package com.banfieldpatin.backend.documentos.almacen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SupabaseAlmacenDocumentosTest {
	private static final byte[] CONTENIDO = "documento-sintetico".getBytes(StandardCharsets.UTF_8);
	private final List<Peticion> peticiones = new CopyOnWriteArrayList<>();
	private final CountDownLatch liberar = new CountDownLatch(1);
	private HttpServer servidor;
	private java.util.concurrent.ExecutorService ejecutor;
	private Function<Peticion, Respuesta> responder;
	private Respuesta respuestaBucket;
	private final List<Peticion> consultasBucket = new CopyOnWriteArrayList<>();

	record Peticion(String metodo, String ruta, String autorizacion, String apiKey, String upsert,
			String mime, byte[] cuerpo) { }
	record Respuesta(int estado, String cuerpo) { }

	@BeforeEach
	void iniciar() throws IOException {
		servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		ejecutor = Executors.newVirtualThreadPerTaskExecutor();
		servidor.setExecutor(ejecutor);
		respuestaBucket = new Respuesta(200, "{\"public\":false}");
		responder = p -> new Respuesta(200, "{\"Key\":\"privado/" + clave(p) + "\"}");
		servidor.createContext("/", intercambio -> {
			try (intercambio) {
				var h = intercambio.getRequestHeaders();
				var p = new Peticion(intercambio.getRequestMethod(), intercambio.getRequestURI().toString(),
						h.getFirst("Authorization"), h.getFirst("apikey"), h.getFirst("x-upsert"),
						h.getFirst("Content-Type"), intercambio.getRequestBody().readAllBytes());
				boolean consulta = p.metodo().equals("GET");
				if (consulta) consultasBucket.add(p); else peticiones.add(p);
				var r = consulta ? respuestaBucket : responder.apply(p);
				if (r.estado() == -2) return;
				if (r.estado() == 0 || r.estado() == -1) {
					if (r.estado() == -1) intercambio.sendResponseHeaders(200, 100);
					try { liberar.await(5, TimeUnit.SECONDS); }
					catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
					return;
				}
				intercambio.getResponseHeaders().set("Location", url() + "/destino-no-autorizado");
				byte[] cuerpo = r.cuerpo().getBytes(StandardCharsets.UTF_8);
				intercambio.sendResponseHeaders(r.estado(), cuerpo.length);
				intercambio.getResponseBody().write(cuerpo);
			}
		});
		servidor.start();
	}

	@AfterEach
	void cerrar() {
		liberar.countDown();
		if (servidor != null) servidor.stop(0);
		if (ejecutor != null) ejecutor.close();
	}

	private String url() { return "http://127.0.0.1:" + servidor.getAddress().getPort(); }
	private SupabaseAlmacenDocumentos adaptador() {
		return new SupabaseAlmacenDocumentos(new StoragePropiedades(url(), "privado", "secreto-sintetico",
				Duration.ofSeconds(1), Duration.ofMillis(500)));
	}
	private String clave(Peticion p) { return p.ruta().substring(p.ruta().lastIndexOf('/') + 1); }

	@Test
	void creaConClavesOpacasDistintasSinNombreNiSobrescritura() {
		var almacen = adaptador();
		var a = almacen.crear(CONTENIDO, "application/pdf");
		var b = almacen.crear(CONTENIDO, "application/pdf");
		assertThat(a.claveInterna()).isNotEqualTo(b.claveInterna());
		assertThat(consultasBucket).hasSize(2).allSatisfy(p ->
				assertThat(p.ruta()).isEqualTo("/storage/v1/bucket/privado"));
		assertThat(peticiones).hasSize(2).allSatisfy(p -> {
			assertThat(p.metodo()).isEqualTo("POST");
			assertThat(p.ruta()).isEqualTo("/storage/v1/object/privado/" + UUID.fromString(clave(p)));
			assertThat(p.upsert()).isEqualTo("false");
			assertThat(p.autorizacion()).isEqualTo("Bearer secreto-sintetico");
			assertThat(p.apiKey()).isEqualTo("secreto-sintetico");
			assertThat(p.mime()).isEqualTo("application/pdf");
			assertThat(p.cuerpo()).isEqualTo(CONTENIDO);
		});
		assertThat(a.toString()).doesNotContain(a.claveInterna().toString());
	}

	@Test
	void compensaSoloUnReciboPropioConfirmadoYUnaSolaVez() {
		var almacen = adaptador();
		var creado = almacen.crear(CONTENIDO, "application/pdf");
		responder = p -> new Respuesta(200, "[{\"name\":\"" + creado.claveInterna() + "\"}]");
		assertThatThrownBy(() -> adaptador().eliminarCreado(creado)).isInstanceOf(FalloAlmacenDocumentos.class);
		assertThat(peticiones).hasSize(1);
		almacen.eliminarCreado(creado);
		var borrado = peticiones.getLast();
		assertThat(borrado.metodo()).isEqualTo("DELETE");
		assertThat(borrado.ruta()).isEqualTo("/storage/v1/object/privado");
		assertThat(new String(borrado.cuerpo(), StandardCharsets.UTF_8))
				.isEqualTo("{\"prefixes\":[\"" + creado.claveInterna() + "\"]}");
		assertThatThrownBy(() -> almacen.eliminarCreado(creado)).isInstanceOf(FalloAlmacenDocumentos.class);
		assertThat(peticiones).hasSize(2);
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403, 409, 400, 429, 500, 503, 302})
	void fallosHttpSonTipadosSinDatosDelProveedorNiReintento(int estado) {
		responder = p -> new Respuesta(estado, "DNI-FICTICIO secreto-sintetico /ruta-proveedor");
		assertThatThrownBy(() -> adaptador().crear(CONTENIDO, "application/pdf"))
				.isInstanceOfSatisfying(FalloAlmacenDocumentos.class, ex -> {
					assertThat(ex.codigo().name()).isEqualTo(estado == 401 || estado == 403 ? "AUTENTICACION"
							: estado == 409 ? "CONFLICTO" : estado >= 500 || estado == 302 ? "RESPUESTA_INESPERADA" : "RECHAZADO");
					assertThat(ex.resultadoIncierto()).isEqualTo(estado >= 500 || estado == 302);
					assertThat(ex.getCause()).isNull();
					assertThat(ex.toString()).doesNotContain("secreto-sintetico", "DNI-FICTICIO", "/ruta-proveedor", url());
				});
		assertThat(peticiones).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "null", "no-json", "{}", "{\"Key\":\"otro/objeto\"}", "[]"})
	void altaConConfirmacionInvalidaEsInciertaSinCompensacionAutomatica(String cuerpo) {
		responder = p -> new Respuesta(200, cuerpo);
		assertThatThrownBy(() -> adaptador().crear(CONTENIDO, "application/pdf"))
				.isInstanceOfSatisfying(FalloAlmacenDocumentos.class, ex -> {
					assertThat(ex.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA);
					assertThat(ex.resultadoIncierto()).isTrue();
				});
		assertThat(peticiones).hasSize(1);
	}

	@Test
	void limitaElTamanoDeRespuestaDelProveedor() {
		responder = p -> new Respuesta(200, "x".repeat(20_000));
		assertThatThrownBy(() -> adaptador().crear(CONTENIDO, "application/pdf"))
				.isInstanceOfSatisfying(FalloAlmacenDocumentos.class, ex -> assertThat(ex.resultadoIncierto()).isTrue());
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1})
	void timeoutDeCabecerasYCuerpoEsAcotadoEIncierto(int modo) {
		responder = p -> new Respuesta(modo, "");
		long inicio = System.nanoTime();
		assertThatThrownBy(() -> adaptador().crear(CONTENIDO, "application/pdf"))
				.isInstanceOfSatisfying(FalloAlmacenDocumentos.class, ex -> {
					assertThat(ex.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.TIMEOUT);
					assertThat(ex.resultadoIncierto()).isTrue();
				});
		assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(3));
		assertThat(peticiones).hasSize(1);
	}

	@Test
	void redCaidaNoConservaCausasConDireccionOCredenciales() {
		var almacen = adaptador();
		servidor.stop(0);
		assertThatThrownBy(() -> almacen.crear(CONTENIDO, "application/pdf"))
				.isInstanceOfSatisfying(FalloAlmacenDocumentos.class, ex -> {
					assertThat(ex.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.RED);
					assertThat(ex.resultadoIncierto()).isFalse();
					assertThat(ex.getCause()).isNull();
				});
	}

	@Test
	void borradoConConfirmacionIncorrectaNoDeclaraCompensacionExitosa() {
		var almacen = adaptador();
		var creado = almacen.crear(CONTENIDO, "application/pdf");
		responder = p -> new Respuesta(200, "[]");
		assertThatThrownBy(() -> almacen.eliminarCreado(creado))
				.isInstanceOfSatisfying(FalloAlmacenDocumentos.class, ex -> assertThat(ex.resultadoIncierto()).isTrue());
		assertThat(peticiones).hasSize(2);
	}

	@Test
	void datosInvalidosNoGeneranTrafico() {
		var almacen = adaptador();
		assertThatThrownBy(() -> almacen.crear(CONTENIDO, null)).isInstanceOf(FalloAlmacenDocumentos.class);
		assertThatThrownBy(() -> almacen.crear(CONTENIDO, "application/pdf;name=persona-ficticia.pdf")).isInstanceOf(FalloAlmacenDocumentos.class);
		assertThatThrownBy(() -> almacen.crear(new byte[0], "application/pdf")).isInstanceOf(FalloAlmacenDocumentos.class);
		assertThatThrownBy(() -> almacen.crear(null, "application/pdf")).isInstanceOf(FalloAlmacenDocumentos.class);
		assertThatThrownBy(() -> almacen.crear(CONTENIDO, "*/*")).isInstanceOf(FalloAlmacenDocumentos.class);
		assertThatThrownBy(() -> almacen.crear(CONTENIDO, "text/plain\r\nX-Token: otro")).isInstanceOf(FalloAlmacenDocumentos.class);
		assertThatThrownBy(() -> almacen.eliminarCreado(null)).isInstanceOf(FalloAlmacenDocumentos.class);
		assertThat(peticiones).isEmpty();
	}
	@ParameterizedTest
	@ValueSource(ints = {401, 403, 404, 500, 302})
	void compensacionFallidaNoSeReintentaNiBorraOtroObjeto(int estado) {
		var almacen = adaptador();
		var creado = almacen.crear(CONTENIDO, "application/pdf");
		responder = p -> new Respuesta(estado, "respuesta-sensible-sintetica");
		assertThatThrownBy(() -> almacen.eliminarCreado(creado))
				.isInstanceOfSatisfying(FalloAlmacenDocumentos.class, ex -> assertThat(ex.resultadoIncierto()).isTrue());
		assertThatThrownBy(() -> almacen.eliminarCreado(creado)).isInstanceOf(FalloAlmacenDocumentos.class);
		assertThat(peticiones).hasSize(2);
	}

	@Test
	void dosCompensacionesConcurrentesConsumenUnSoloRecibo() throws Exception {
		var almacen = adaptador();
		var creado = almacen.crear(CONTENIDO, "application/pdf");
		responder = p -> new Respuesta(200, "[{\"name\":\"" + creado.claveInterna() + "\"}]");
		var salir = new CountDownLatch(1);
		try (var tareas = Executors.newVirtualThreadPerTaskExecutor()) {
			java.util.concurrent.Callable<Boolean> compensar = () -> {
				salir.await();
				try { almacen.eliminarCreado(creado); return true; }
				catch (FalloAlmacenDocumentos ex) { return false; }
			};
			var a = tareas.submit(compensar);
			var b = tareas.submit(compensar);
			salir.countDown();
			assertThat(List.of(a.get(3, TimeUnit.SECONDS), b.get(3, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(true, false);
		}
		assertThat(peticiones).hasSize(2);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"public\":true}", "{}", "{\"public\":\"false\"}", "no-json"})
	void nuncaCreaSiNoConfirmaQueElBucketEsPrivado(String respuesta) {
		respuestaBucket = new Respuesta(200, respuesta);
		assertThatThrownBy(() -> adaptador().crear(CONTENIDO, "application/pdf"))
				.isInstanceOfSatisfying(FalloAlmacenDocumentos.class, ex -> assertThat(ex.resultadoIncierto()).isFalse());
		assertThat(peticiones).isEmpty();
		assertThat(consultasBucket).hasSize(1);
	}

	@Test
	void conexionPerdidaDespuesDeRecibirElPostDejaResultadoIncierto() {
		responder = p -> new Respuesta(-2, "");
		assertThatThrownBy(() -> adaptador().crear(CONTENIDO, "application/pdf"))
				.isInstanceOfSatisfying(FalloAlmacenDocumentos.class, ex -> {
					assertThat(ex.codigo()).isEqualTo(FalloAlmacenDocumentos.Codigo.RED);
					assertThat(ex.resultadoIncierto()).isTrue();
					assertThat(ex.getCause()).isNull();
				});
		assertThat(peticiones).hasSize(1);
	}

}
