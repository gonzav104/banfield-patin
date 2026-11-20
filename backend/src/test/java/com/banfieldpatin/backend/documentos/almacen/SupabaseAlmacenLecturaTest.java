package com.banfieldpatin.backend.documentos.almacen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Lectura aditiva del puerto (U16) contra un doble HTTP crudo: permite declarar longitudes falsas,
 * cortar el cuerpo o emitirlo sin fin, cosas que un servidor HTTP correcto no haría.
 */
class SupabaseAlmacenLecturaTest {
	private static final byte[] CONTENIDO = "documento-sintetico".getBytes(StandardCharsets.UTF_8);
	private static final Duration RESPUESTA = Duration.ofMillis(800);
	private final List<String> peticiones = new CopyOnWriteArrayList<>();
	private final List<Map<String, String>> cabeceras = new CopyOnWriteArrayList<>();
	private final CountDownLatch liberar = new CountDownLatch(1);
	private final AtomicLong enviadosSinFin = new AtomicLong();
	private ServerSocket servidor;
	private volatile String bucket = "{\"public\":false}";
	private volatile Objeto objeto = salida -> responder(salida, 200, "application/pdf", CONTENIDO);

	@FunctionalInterface
	interface Objeto { void escribir(OutputStream salida) throws IOException; }

	@BeforeEach
	void iniciar() throws IOException {
		servidor = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
		Thread.ofVirtual().start(() -> {
			while (!servidor.isClosed()) {
				try {
					Socket s = servidor.accept();
					Thread.ofVirtual().start(() -> atender(s));
				} catch (IOException ex) { return; }
			}
		});
	}

	@AfterEach
	void cerrar() throws IOException {
		liberar.countDown();
		servidor.close();
	}

	private void atender(Socket s) {
		try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
			var linea = new StringBuilder();
			var encabezado = new ByteArrayOutputStream();
			int b, fin = 0;
			while (fin < 4 && (b = in.read()) != -1) {
				encabezado.write(b);
				fin = (b == '\r' || b == '\n') ? fin + 1 : 0;
			}
			String[] lineas = encabezado.toString(StandardCharsets.ISO_8859_1).split("\r\n");
			linea.append(lineas[0]);
			var h = new java.util.HashMap<String, String>();
			for (int i = 1; i < lineas.length; i++) {
				int dp = lineas[i].indexOf(':');
				if (dp > 0) h.put(lineas[i].substring(0, dp).trim().toLowerCase(), lineas[i].substring(dp + 1).trim());
			}
			String ruta = linea.toString().split(" ")[1];
			peticiones.add(linea.toString().split(" ")[0] + " " + ruta);
			cabeceras.add(h);
			if (ruta.startsWith("/storage/v1/bucket/")) {
				responder(out, 200, "application/json", bucket.getBytes(StandardCharsets.UTF_8));
			} else {
				objeto.escribir(out);
			}
			out.flush();
		} catch (IOException ex) {
			// El cliente abortó la lectura: es el comportamiento esperado en varios casos.
		}
	}

	private static void responder(OutputStream salida, int estado, String tipo, byte[] cuerpo) throws IOException {
		String cabecera = "HTTP/1.1 " + estado + " X\r\nConnection: close\r\n"
				+ (tipo == null ? "" : "Content-Type: " + tipo + "\r\n")
				+ "Content-Length: " + cuerpo.length + "\r\n\r\n";
		salida.write(cabecera.getBytes(StandardCharsets.ISO_8859_1));
		salida.write(cuerpo);
	}

	private void esperar() {
		try { liberar.await(5, TimeUnit.SECONDS); }
		catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
	}

	private String url() { return "http://127.0.0.1:" + servidor.getLocalPort(); }

	private SupabaseAlmacenDocumentos adaptador() {
		return new SupabaseAlmacenDocumentos(new StoragePropiedades(url(), "privado", "secreto-sintetico",
				Duration.ofSeconds(1), RESPUESTA));
	}

	private static DestinoPreparado destino() { return DestinoPreparado.desdeRegistro("privado", UUID.randomUUID()); }

	private static void fallaCon(Runnable accion, FalloAlmacenDocumentos.Codigo codigo) {
		assertThatThrownBy(accion::run).isInstanceOfSatisfying(FalloAlmacenDocumentos.class, ex -> {
			assertThat(ex.codigo()).isEqualTo(codigo);
			assertThat(ex.getCause()).isNull();
			assertThat(ex.toString()).doesNotContain("secreto-sintetico", "documento-sintetico", "DNI-FICTICIO");
		});
	}

	@Test
	void leeElObjetoCompletoPorLaRutaAutenticadaTrasConfirmarElBucketPrivado() {
		var d = destino();
		ContenidoLeido leido = adaptador().leer(d, CONTENIDO.length);
		assertThat(leido.contenido()).isEqualTo(CONTENIDO);
		assertThat(leido.tamanioBytes()).isEqualTo(CONTENIDO.length);
		assertThat(leido.tipoContenido()).isEqualTo("application/pdf");
		assertThat(leido.toString()).isEqualTo("ContenidoLeido[privado]");
		assertThat(peticiones).containsExactly("GET /storage/v1/bucket/privado",
				"GET /storage/v1/object/authenticated/privado/" + d.objetoIdInterno());
		assertThat(cabeceras).allSatisfy(h -> {
			assertThat(h.get("authorization")).isEqualTo("Bearer secreto-sintetico");
			assertThat(h.get("apikey")).isEqualTo("secreto-sintetico");
		});
	}

	@Test
	void unObjetoMenorQueElMaximoSeDevuelveSinInterpretarlo() {
		// El tamaño exacto lo verifica el llamador contra su manifiesto, no el adaptador.
		assertThat(adaptador().leer(destino(), 1000).contenido()).isEqualTo(CONTENIDO);
	}

	@Test
	void sinTipoDeclaradoNoSeInventaUno() {
		objeto = salida -> responder(salida, 200, null, CONTENIDO);
		assertThat(adaptador().leer(destino(), CONTENIDO.length).tipoContenido()).isEqualTo("application/octet-stream");
	}

	@Test
	void unaLongitudDeclaradaMayorQueElMaximoSeRechazaSinLeerElCuerpo() {
		// Declara un cuerpo enorme y no lo envía: si el adaptador lo esperara, vencería el plazo (TIMEOUT).
		objeto = salida -> {
			salida.write(("HTTP/1.1 200 X\r\nContent-Type: application/pdf\r\nContent-Length: 999999999\r\n\r\n")
					.getBytes(StandardCharsets.ISO_8859_1));
			salida.flush();
			esperar();
		};
		long inicio = System.nanoTime();
		fallaCon(() -> adaptador().leer(destino(), CONTENIDO.length), FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA);
		assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(RESPUESTA);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void unCuerpoSinLongitudQueSuperaElMaximoSeAbortaAlExcederlo(boolean fragmentado) {
		// Emite sin fin (fragmentado, o hasta cerrar la conexión): sólo un límite estricto termina antes del plazo.
		objeto = salida -> {
			salida.write(("HTTP/1.1 200 X\r\nContent-Type: application/pdf\r\nConnection: close\r\n"
					+ (fragmentado ? "Transfer-Encoding: chunked\r\n" : "") + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
			byte[] bloque = new byte[1024];
			while (true) {
				if (fragmentado) salida.write("400\r\n".getBytes(StandardCharsets.ISO_8859_1));
				salida.write(bloque);
				if (fragmentado) salida.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
				salida.flush();
				enviadosSinFin.addAndGet(bloque.length);
			}
		};
		long inicio = System.nanoTime();
		fallaCon(() -> adaptador().leer(destino(), 4096), FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA);
		assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(RESPUESTA);
	}

	@Test
	void menosBytesQueLosDeclaradosEsUnaRespuestaInesperada() {
		objeto = salida -> {
			salida.write(("HTTP/1.1 200 X\r\nContent-Type: application/pdf\r\nContent-Length: " + CONTENIDO.length
					+ "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			salida.write(CONTENIDO, 0, 5);
		};
		fallaCon(() -> adaptador().leer(destino(), CONTENIDO.length), FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA);
	}

	@Test
	void unaLongitudIlegibleODuplicadaEsUnaRespuestaInesperada() {
		objeto = salida -> salida.write(("HTTP/1.1 200 X\r\nContent-Type: application/pdf\r\nContent-Length: +19\r\n"
				+ "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
		fallaCon(() -> adaptador().leer(destino(), CONTENIDO.length), FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA);
	}

	@Test
	void pideElObjetoSinCompresionYRechazaUnaCodificacionQueNoSabeLeer() {
		objeto = salida -> {
			salida.write(("HTTP/1.1 200 X\r\nContent-Type: application/pdf\r\nContent-Encoding: br\r\nContent-Length: "
					+ CONTENIDO.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			salida.write(CONTENIDO);
		};
		fallaCon(() -> adaptador().leer(destino(), CONTENIDO.length), FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA);
		assertThat(cabeceras.getLast().get("accept-encoding")).isEqualTo("identity");
	}

	@Test
	void unCuerpoComprimidoIgualmenteSeLimitaPorSusBytesDescomprimidos() {
		// Unos pocos bytes comprimidos que se expanden mucho más allá del máximo.
		var comprimido = new ByteArrayOutputStream();
		try (var gzip = new java.util.zip.GZIPOutputStream(comprimido)) { gzip.write(new byte[200_000]); }
		catch (IOException ex) { throw new IllegalStateException(ex); }
		byte[] cuerpo = comprimido.toByteArray();
		objeto = salida -> {
			salida.write(("HTTP/1.1 200 X\r\nContent-Type: application/pdf\r\nContent-Encoding: gzip\r\nContent-Length: "
					+ cuerpo.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			salida.write(cuerpo);
		};
		assertThat(cuerpo.length).isLessThan(4096);
		fallaCon(() -> adaptador().leer(destino(), 4096), FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA);
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 404, 401, 403, 409, 500, 503, 302})
	void fallosHttpSeClasificanSinDatosDelProveedor(int estado) {
		objeto = salida -> responder(salida, estado, "text/plain",
				"DNI-FICTICIO secreto-sintetico /ruta-proveedor".getBytes(StandardCharsets.UTF_8));
		var esperado = estado == 401 || estado == 403 ? FalloAlmacenDocumentos.Codigo.AUTENTICACION
				: estado == 409 ? FalloAlmacenDocumentos.Codigo.CONFLICTO
				: estado < 500 && estado >= 400 ? FalloAlmacenDocumentos.Codigo.RECHAZADO
				: FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA;
		fallaCon(() -> adaptador().leer(destino(), CONTENIDO.length), esperado);
		assertThat(peticiones).hasSize(2);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void cabecerasOCuerpoDetenidosVencenComoTimeout(boolean conCabeceras) {
		objeto = salida -> {
			if (conCabeceras) {
				salida.write(("HTTP/1.1 200 X\r\nContent-Type: application/pdf\r\nContent-Length: " + CONTENIDO.length
						+ "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
				salida.write(CONTENIDO, 0, 3);
				salida.flush();
			}
			esperar();
		};
		long inicio = System.nanoTime();
		fallaCon(() -> adaptador().leer(destino(), CONTENIDO.length), FalloAlmacenDocumentos.Codigo.TIMEOUT);
		assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(RESPUESTA.multipliedBy(4));
	}

	@Test
	void redCaidaEsUnFalloDeRed() throws IOException {
		var almacen = adaptador();
		servidor.close();
		fallaCon(() -> almacen.leer(destino(), CONTENIDO.length), FalloAlmacenDocumentos.Codigo.RED);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"public\":true}", "{}", "no-json"})
	void nuncaLeeSiNoConfirmaQueElBucketEsPrivado(String respuesta) {
		bucket = respuesta;
		fallaCon(() -> adaptador().leer(destino(), CONTENIDO.length),
				respuesta.equals("no-json") ? FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA : FalloAlmacenDocumentos.Codigo.RECHAZADO);
		assertThat(peticiones).containsExactly("GET /storage/v1/bucket/privado");
	}

	@Test
	void destinoAjenoOMaximoInvalidoNoGeneranTrafico() {
		var almacen = adaptador();
		fallaCon(() -> almacen.leer(DestinoPreparado.desdeRegistro("otro", UUID.randomUUID()), 10),
				FalloAlmacenDocumentos.Codigo.ENTRADA_INVALIDA);
		fallaCon(() -> almacen.leer(null, 10), FalloAlmacenDocumentos.Codigo.ENTRADA_INVALIDA);
		for (long max : new long[] {0, -1, 50_000_001L, Long.MAX_VALUE})
			fallaCon(() -> almacen.leer(destino(), max), FalloAlmacenDocumentos.Codigo.ENTRADA_INVALIDA);
		assertThat(peticiones).isEmpty();
	}
}
