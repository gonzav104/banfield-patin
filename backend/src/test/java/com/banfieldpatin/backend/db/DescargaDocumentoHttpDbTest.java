package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.usuarios.Rol;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

/**
 * Descarga administrativa (U16) por HTTP real contra PostgreSQL 17 exclusiva y un Storage sintético.
 * Los documentos se publican con el mismo registro durable que la carga (operación PUBLICADA y su
 * manifiesto); el Storage sintético puede devolver otros bytes, fallar o detenerse.
 */
@Tag("db")
@ActiveProfiles({ "test", "e2e" })
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class DescargaDocumentoHttpDbTest {
	static final String BASE = PostgresDescartable.crearBase("descarga_http");
	static final HttpServer STORAGE;
	static final Path TOMCAT;
	/** Clave del objeto → bytes que devuelve el Storage sintético (pueden no ser los publicados). */
	static final Map<String, byte[]> OBJETOS = new ConcurrentHashMap<>();
	static final AtomicInteger LECTURAS = new AtomicInteger();
	static final AtomicInteger ESTADO = new AtomicInteger(200);
	static final AtomicReference<Runnable> HOOK = new AtomicReference<>(() -> {});
	/** Storage lento: declara el objeto y lo gotea byte a byte hasta que el adaptador corta. */
	static final java.util.concurrent.atomic.AtomicBoolean GOTEO = new java.util.concurrent.atomic.AtomicBoolean();
	static final CountDownLatch[] GOTEO_CORTADO = { new CountDownLatch(1) };
	static final String CREDENCIAL = "credencial-solo-sintetica";

	static {
		try {
			TOMCAT = Files.createTempDirectory("descarga-http-");
			STORAGE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			STORAGE.createContext("/storage/v1/", x -> {
				try (x) {
					String ruta = x.getRequestURI().getPath();
					byte[] cuerpo;
					if (ruta.equals("/storage/v1/bucket/privado")) {
						cuerpo = "{\"id\":\"privado\",\"public\":false}".getBytes(StandardCharsets.UTF_8);
						x.getResponseHeaders().set("Content-Type", "application/json");
						x.sendResponseHeaders(200, cuerpo.length);
						x.getResponseBody().write(cuerpo);
						return;
					}
					// HEAD del objeto (inspección): tamaño y tipo actuales, sin contar como lectura.
					if (x.getRequestMethod().equals("HEAD") && ruta.startsWith("/storage/v1/object/authenticated/privado/")
							&& ("Bearer " + CREDENCIAL).equals(x.getRequestHeaders().getFirst("Authorization"))) {
						byte[] actual = OBJETOS.get(ruta.substring(ruta.lastIndexOf('/') + 1));
						if (actual == null) {
							x.sendResponseHeaders(400, -1);
							return;
						}
						x.getResponseHeaders().set("Content-Type", "image/jpeg");
						x.getResponseHeaders().set("Content-Length", String.valueOf(actual.length));
						x.sendResponseHeaders(200, -1);
						return;
					}
					if (!x.getRequestMethod().equals("GET") || !ruta.startsWith("/storage/v1/object/authenticated/privado/")
							|| !("Bearer " + CREDENCIAL).equals(x.getRequestHeaders().getFirst("Authorization"))) {
						x.sendResponseHeaders(418, -1);
						return;
					}
					LECTURAS.incrementAndGet();
					HOOK.get().run();
					cuerpo = OBJETOS.get(ruta.substring(ruta.lastIndexOf('/') + 1));
					if (ESTADO.get() != 200 || cuerpo == null) {
						x.sendResponseHeaders(cuerpo == null ? 400 : ESTADO.get(), -1);
						return;
					}
					x.getResponseHeaders().set("Content-Type", "application/octet-stream");
					x.sendResponseHeaders(200, cuerpo.length);
					if (GOTEO.get()) {
						try {
							for (int i = 0; i < cuerpo.length; i++) {
								x.getResponseBody().write(cuerpo[i]);
								x.getResponseBody().flush();
								Thread.sleep(100);
							}
						} catch (IOException ex) {
							GOTEO_CORTADO[0].countDown();
							throw ex;
						} catch (InterruptedException ex) {
							Thread.currentThread().interrupt();
						}
						return;
					}
					x.getResponseBody().write(cuerpo);
				} catch (IOException ex) {
					// El adaptador puede cortar la lectura: es parte de lo que se prueba.
				}
			});
			STORAGE.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			STORAGE.start();
		} catch (IOException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

	@DynamicPropertySource
	static void propiedades(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", () -> PostgresDescartable.urlDeBase(BASE));
		r.add("spring.datasource.username", PostgresDescartable::usuario);
		r.add("spring.datasource.password", PostgresDescartable::clave);
		r.add("spring.flyway.enabled", () -> true);
		r.add("server.tomcat.basedir", TOMCAT::toString);
		r.add("banfield.documentos.storage.habilitado", () -> true);
		r.add("banfield.documentos.storage.url", () -> "http://127.0.0.1:" + STORAGE.getAddress().getPort());
		r.add("banfield.documentos.storage.bucket", () -> "privado");
		r.add("banfield.documentos.storage.credencial", () -> CREDENCIAL);
		r.add("banfield.documentos.storage.respuesta", () -> "PT3S");
		// Un solo permiso: la ocupación se prueba sin carreras.
		r.add("banfield.documentos.descarga.concurrencia", () -> 1);
	}

	@MockitoSpyBean
	AuditoriaService auditoria;
	@Autowired
	javax.sql.DataSource fuente;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;
	@LocalServerPort
	int puerto;
	UUID escuela, admin, atleta;
	String sesion;
	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void datos() {
		OBJETOS.clear();
		LECTURAS.set(0);
		ESTADO.set(200);
		HOOK.set(() -> {});
		GOTEO.set(false);
		GOTEO_CORTADO[0] = new CountDownLatch(1);
		org.mockito.Mockito.reset(auditoria);
		var d = new DatosDb(jdbc);
		escuela = d.escuela("descarga-" + UUID.randomUUID());
		admin = d.admin(escuela, "admin-" + UUID.randomUUID() + "@example.invalid", true);
		atleta = d.deportista(escuela, "SINTETICO", "Prueba", "Atleta");
		sesion = tokens.emitir(admin, Rol.ADMIN, escuela, null);
	}

	@AfterAll
	void cerrar() throws IOException {
		STORAGE.stop(0);
		if (fuente instanceof com.zaxxer.hikari.HikariDataSource pool) pool.close();
		PostgresDescartable.eliminarBase(BASE);
		try (var archivos = Files.walk(TOMCAT)) {
			for (Path p : archivos.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
		}
	}

	// ---------- datos: publicación con el mismo registro durable que la carga ----------

	record Publicado(UUID id, String clave, byte[] bytes) { }

	private Publicado publicar(UUID deportista, String tipo, String mime, byte[] bytes) {
		UUID objeto = UUID.randomUUID();
		UUID id = jdbc.sql("""
				INSERT INTO gestion_patin.operacion_carga_documental(escuela_id,solicitante_id,deportista_id,tipo,
				    storage_bucket,objeto_id,idempotencia)
				VALUES(:e,:u,:d,:t,'privado',:o,:i) RETURNING id
				""").param("e", escuela).param("u", admin).param("d", deportista).param("t", tipo).param("o", objeto)
				.param("i", UUID.randomUUID()).query(UUID.class).single();
		cambiar(id, "reserva_hasta=clock_timestamp()+interval '1 minute'", Map.of());
		cambiar(id, "sha256_archivo=:sha,mime_type=:m,tamanio_bytes=:n", Map.of("sha", sha(bytes), "m", mime, "n", (long) bytes.length));
		cambiar(id, "estado='TRANSFIRIENDO'", Map.of());
		cambiar(id, "estado='ALMACENADA'", Map.of());
		jdbc.sql("""
				INSERT INTO gestion_patin.documento(id,escuela_id,deportista_id,tipo,nombre_original,storage_bucket,
				    storage_key,mime_type,tamanio_bytes,estado,vigente,cargado_por,serie_id,version)
				VALUES(:id,:e,:d,:t,'documento','privado',:k,:m,:n,'CARGADO',true,:u,:id,0)
				""").param("id", id).param("e", escuela).param("d", deportista).param("t", tipo)
				.param("k", objeto.toString()).param("m", mime).param("n", (long) bytes.length).param("u", admin).update();
		cambiar(id, "estado='PUBLICADA',documento_id=id,reserva_hasta=NULL", Map.of());
		OBJETOS.put(objeto.toString(), bytes);
		return new Publicado(id, objeto.toString(), bytes);
	}

	private void cambiar(UUID id, String asignaciones, Map<String, Object> parametros) {
		long version = jdbc.sql("SELECT version FROM gestion_patin.operacion_carga_documental WHERE id=:id")
				.param("id", id).query(Long.class).single();
		var sql = jdbc.sql("UPDATE gestion_patin.operacion_carga_documental SET " + asignaciones
				+ ",version=version+1 WHERE id=:id AND version=:v").param("id", id).param("v", version);
		for (var p : parametros.entrySet()) sql = sql.param(p.getKey(), p.getValue());
		assertThat(sql.update()).isEqualTo(1);
	}

	/** Documento anterior a la carga durable: sin operación publicada ni SHA. */
	private UUID heredado(UUID deportista, boolean vigente) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.documento(escuela_id,deportista_id,tipo,nombre_original,storage_bucket,storage_key,
				    mime_type,tamanio_bytes,vigente,cargado_por)
				VALUES(:e,:d,'OTRO','NOMBRE_PRIVADO.pdf','privado',:k,'application/pdf',10,:v,:u) RETURNING id
				""").param("e", escuela).param("d", deportista).param("k", UUID.randomUUID().toString())
				.param("v", vigente).param("u", admin).query(UUID.class).single();
	}

	private static byte[] sha(byte[] b) {
		try { return MessageDigest.getInstance("SHA-256").digest(b); }
		catch (Exception ex) { throw new IllegalStateException(ex); }
	}

	private static byte[] bytes(String texto, int repeticiones) {
		return texto.repeat(repeticiones).getBytes(StandardCharsets.US_ASCII);
	}

	// ---------- HTTP ----------

	private String[] csrf() throws Exception {
		var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + "/api/auth/csrf")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		String cookie = r.headers().allValues("set-cookie").stream().map(s -> s.split(";", 2)[0])
				.filter(s -> s.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
		return new String[] { cookie, cookie.substring(cookie.indexOf('=') + 1) };
	}

	private String ruta(UUID deportista, UUID documento) {
		return "/api/admin/deportistas/" + deportista + "/documentos/" + documento + "/archivo";
	}

	private HttpResponse<byte[]> descargar(UUID deportista, UUID documento) throws Exception {
		return descargar(deportista, documento, sesion, true);
	}

	private HttpResponse<byte[]> descargar(UUID deportista, UUID documento, String token, boolean conCsrf) throws Exception {
		String[] x = csrf();
		var pedido = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + ruta(deportista, documento)))
				.timeout(Duration.ofSeconds(20)).header("Cookie", x[0] + "; BP_SESION=" + token).GET();
		if (conCsrf) pedido.header("X-XSRF-TOKEN", x[1]);
		return http.send(pedido.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	private static String codigo(HttpResponse<byte[]> r) {
		return JsonPath.read(new String(r.body(), StandardCharsets.UTF_8), "$.codigo");
	}

	private List<String> auditadas(UUID documento) {
		return jdbc.sql("SELECT accion FROM gestion_patin.auditoria WHERE escuela_id=:e AND recurso_id=:d ORDER BY id")
				.param("e", escuela).param("d", documento).query(String.class).list();
	}

	private static void sinContenido(HttpResponse<byte[]> r, Publicado p) {
		String cuerpo = new String(r.body(), StandardCharsets.ISO_8859_1);
		assertThat(r.headers().firstValue("content-type").orElse("")).startsWith("application/json");
		assertThat(r.headers().firstValue("content-disposition")).isEmpty();
		assertThat(cuerpo).doesNotContain(new String(p.bytes(), 0, Math.min(8, p.bytes().length), StandardCharsets.ISO_8859_1))
				.doesNotContain(p.clave(), "privado", CREDENCIAL, "storage");
	}

	// ---------- pruebas ----------

	@Test
	void adminDescargaLosBytesVerificadosConCabecerasSegurasYAuditoria() throws Exception {
		var p = publicar(atleta, "DNI_DEPORTISTA_FRENTE", "image/jpeg", bytes("JPEG-SINTETICO-", 100));
		var r = descargar(atleta, p.id());
		assertThat(r.statusCode()).isEqualTo(200);
		assertThat(r.body()).isEqualTo(p.bytes());
		assertThat(r.headers().firstValue("content-type")).hasValue("image/jpeg");
		assertThat(r.headers().firstValue("content-length")).hasValue(String.valueOf(p.bytes().length));
		assertThat(r.headers().firstValue("content-disposition")).hasValue("attachment; filename=\"dni-frente.jpg\"");
		assertThat(r.headers().allValues("x-content-type-options")).containsExactly("nosniff");
		assertThat(r.headers().allValues("cache-control")).containsExactly("no-store");
		assertThat(r.headers().firstValue("content-security-policy")).hasValue("sandbox; default-src 'none'");
		assertThat(LECTURAS).hasValue(1);
		assertThat(auditadas(p.id())).containsExactly("DOCUMENTO_DESCARGADO");
		var fila = jdbc.sql("SELECT usuario_id, recurso_tipo, detalle::text FROM gestion_patin.auditoria WHERE recurso_id=:d")
				.param("d", p.id()).query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3))).single();
		assertThat(fila.get(0)).isEqualTo(admin.toString());
		assertThat(fila.get(1)).isEqualTo("DOCUMENTO");
		assertThat(fila.get(2)).contains(atleta.toString(), "DNI_DEPORTISTA_FRENTE").doesNotContain(p.clave(), "privado");
	}

	@Test
	void pdfYPngConservanElTipoRegistradoYUnNombreNeutro() throws Exception {
		var pdf = publicar(atleta, "DNI_DEPORTISTA_DORSO", "application/pdf", bytes("%PDF-sintetico ", 10));
		var png = publicar(atleta, "OTRO", "image/png", bytes("PNG-SINTETICO", 10));
		var a = descargar(atleta, pdf.id());
		var b = descargar(atleta, png.id());
		assertThat(a.headers().firstValue("content-type")).hasValue("application/pdf");
		assertThat(a.headers().firstValue("content-disposition")).hasValue("attachment; filename=\"dni-dorso.pdf\"");
		assertThat(b.headers().firstValue("content-type")).hasValue("image/png");
		assertThat(b.headers().firstValue("content-disposition")).hasValue("attachment; filename=\"documento.png\"");
		assertThat(a.body()).isEqualTo(pdf.bytes());
		assertThat(b.body()).isEqualTo(png.bytes());
	}

	@Test
	void otraEscuelaOtroDeportistaNoVigenteOInexistenteSonUn404Uniforme() throws Exception {
		var p = publicar(atleta, "DNI_DEPORTISTA_FRENTE", "image/jpeg", bytes("JPEG-", 50));
		var d = new DatosDb(jdbc);
		UUID otraEscuela = d.escuela("ajena-" + UUID.randomUUID());
		UUID adminAjeno = d.admin(otraEscuela, "ajeno-" + UUID.randomUUID() + "@example.invalid", true);
		UUID otroAtleta = d.deportista(escuela, "SINTETICO2", "Otra", "Atleta");
		var ajena = descargar(atleta, p.id(), tokens.emitir(adminAjeno, Rol.ADMIN, otraEscuela, null), true);
		var otroDeportista = descargar(otroAtleta, p.id());
		var inexistente = descargar(atleta, UUID.randomUUID());
		var noVigente = publicar(atleta, "OTRO", "application/pdf", bytes("%PDF-", 10));
		jdbc.sql("UPDATE gestion_patin.documento SET vigente=false WHERE id=:id").param("id", noVigente.id()).update();
		var retirado = descargar(atleta, noVigente.id());
		for (var r : List.of(ajena, otroDeportista, inexistente, retirado)) {
			assertThat(r.statusCode()).isEqualTo(404);
			assertThat(codigo(r)).isEqualTo("DOCUMENTO_NO_ENCONTRADO");
			sinContenido(r, p);
		}
		assertThat(LECTURAS).hasValue(0);
		assertThat(auditadas(p.id())).isEmpty();
	}

	@Test
	void unDocumentoSinOperacionPublicadaOAlteradoNoEsVerificable() throws Exception {
		UUID legado = heredado(atleta, true);
		var r = descargar(atleta, legado);
		assertThat(r.statusCode()).isEqualTo(409);
		assertThat(codigo(r)).isEqualTo("INTEGRIDAD_NO_VERIFICABLE");
		// Un registro que ya no coincide con su manifiesto publicado tampoco se sirve.
		var p = publicar(atleta, "DNI_DEPORTISTA_FRENTE", "image/jpeg", bytes("JPEG-", 50));
		jdbc.sql("UPDATE gestion_patin.documento SET tamanio_bytes=tamanio_bytes+1 WHERE id=:id").param("id", p.id()).update();
		var alterado = descargar(atleta, p.id());
		assertThat(alterado.statusCode()).isEqualTo(409);
		assertThat(codigo(alterado)).isEqualTo("INTEGRIDAD_NO_VERIFICABLE");
		assertThat(LECTURAS).hasValue(0);
		assertThat(auditadas(legado)).isEmpty();
	}

	@Test
	void bytesAlteradosOMasCortosSonIntegridadFallidaSinEntregarContenido() throws Exception {
		var p = publicar(atleta, "DNI_DEPORTISTA_FRENTE", "image/jpeg", bytes("JPEG-ORIGINAL-", 40));
		byte[] alterado = p.bytes().clone();
		alterado[alterado.length - 1] ^= 1;
		OBJETOS.put(p.clave(), alterado);
		var r = descargar(atleta, p.id());
		assertThat(r.statusCode()).isEqualTo(502);
		assertThat(codigo(r)).isEqualTo("INTEGRIDAD_FALLIDA");
		sinContenido(r, p);
		OBJETOS.put(p.clave(), java.util.Arrays.copyOf(p.bytes(), 10));
		var corto = descargar(atleta, p.id());
		assertThat(corto.statusCode()).isEqualTo(502);
		assertThat(codigo(corto)).isEqualTo("INTEGRIDAD_FALLIDA");
		sinContenido(corto, p);
		assertThat(auditadas(p.id())).containsExactly("DOCUMENTO_INTEGRIDAD_FALLIDA", "DOCUMENTO_INTEGRIDAD_FALLIDA");
	}

	@Test
	void unFalloSecundarioAlAuditarLaIntegridadConservaEl502SinContenido(CapturedOutput salida) throws Exception {
		var p = publicar(atleta, "DNI_DEPORTISTA_FRENTE", "image/jpeg", bytes("JPEG-ORIGINAL-", 40));
		byte[] alterado = p.bytes().clone();
		alterado[0] ^= 1;
		OBJETOS.put(p.clave(), alterado);
		org.mockito.Mockito.doThrow(new IllegalStateException("registro secundario caido")).when(auditoria)
				.registrarFallo(org.mockito.ArgumentMatchers.any());
		var r = descargar(atleta, p.id());
		assertThat(r.statusCode()).isEqualTo(502);
		assertThat(codigo(r)).isEqualTo("INTEGRIDAD_FALLIDA");
		sinContenido(r, p);
		assertThat(auditadas(p.id())).isEmpty();
		assertThat(salida.getAll()).contains("fase=auditoria-integridad")
				.doesNotContain("registro secundario caido", p.clave(), CREDENCIAL);
		// El permiso (concurrencia 1) quedó libre.
		OBJETOS.put(p.clave(), p.bytes());
		assertThat(descargar(atleta, p.id()).statusCode()).isEqualTo(200);
	}

	@Test
	void unStorageLentoVenceSinEntregarBytesYLaCapacidadSeRecupera() throws Exception {
		var p = publicar(atleta, "OTRO", "application/pdf", bytes("%PDF-LENTO-", 200));
		GOTEO.set(true);
		long inicio = System.nanoTime();
		var lenta = descargar(atleta, p.id());
		assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(15));
		assertThat(lenta.statusCode()).isEqualTo(503);
		assertThat(codigo(lenta)).isEqualTo("DESCARGA_NO_DISPONIBLE");
		sinContenido(lenta, p);
		// La lectura se abortó de verdad: Storage ve la conexión cerrada.
		assertThat(GOTEO_CORTADO[0].await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(auditadas(p.id())).isEmpty();
		GOTEO.set(false);
		// Concurrencia 1: si el permiso no se hubiera liberado, sería 429.
		var siguiente = descargar(atleta, p.id());
		assertThat(siguiente.statusCode()).isEqualTo(200);
		assertThat(siguiente.body()).isEqualTo(p.bytes());
		assertThat(auditadas(p.id())).containsExactly("DOCUMENTO_DESCARGADO");
	}

	@Test
	void unFalloOUnObjetoAusenteEnStorageNoEstaDisponibleYNoAudita(CapturedOutput salida) throws Exception {
		var p = publicar(atleta, "DNI_DEPORTISTA_FRENTE", "image/jpeg", bytes("JPEG-", 40));
		ESTADO.set(500);
		var caido = descargar(atleta, p.id());
		ESTADO.set(200);
		OBJETOS.remove(p.clave());
		var ausente = descargar(atleta, p.id());
		for (var r : List.of(caido, ausente)) {
			assertThat(r.statusCode()).isEqualTo(503);
			assertThat(codigo(r)).isEqualTo("DESCARGA_NO_DISPONIBLE");
			sinContenido(r, p);
		}
		assertThat(auditadas(p.id())).isEmpty();
		assertThat(salida.getAll()).contains("fase=lectura").doesNotContain(p.clave(), CREDENCIAL);
	}

	/**
	 * Un objeto MAYOR que el registrado lo rechaza la lectura acotada; la inspección (HEAD) confirma un tamaño distinto,
	 * así que es un fallo de integridad (502 auditado), no una indisponibilidad, y nunca se entregan bytes.
	 */
	@Test
	void unObjetoMayorQueElRegistradoEsUnFalloDeIntegridadAuditado() throws Exception {
		var p = publicar(atleta, "DNI_DEPORTISTA_FRENTE", "image/jpeg", bytes("JPEG-", 40));
		OBJETOS.put(p.clave(), bytes("JPEG-", 41));
		var mayor = descargar(atleta, p.id());
		assertThat(mayor.statusCode()).isEqualTo(502);
		assertThat(codigo(mayor)).isEqualTo("INTEGRIDAD_FALLIDA");
		sinContenido(mayor, p);
		assertThat(auditadas(p.id())).containsExactly("DOCUMENTO_INTEGRIDAD_FALLIDA");
	}

	@Test
	void siLaAuditoriaObligatoriaFallaNoSeEnviaNingunByte() throws Exception {
		var p = publicar(atleta, "DNI_DEPORTISTA_FRENTE", "image/jpeg", bytes("JPEG-", 40));
		org.mockito.Mockito.doThrow(new IllegalStateException("auditoria no disponible")).when(auditoria)
				.registrar(org.mockito.ArgumentMatchers.argThat(
						(EventoAuditoria e) -> e != null && e.accion() == AccionAuditoria.DOCUMENTO_DESCARGADO));
		var r = descargar(atleta, p.id());
		assertThat(r.statusCode()).isEqualTo(503);
		assertThat(codigo(r)).isEqualTo("DESCARGA_NO_DISPONIBLE");
		sinContenido(r, p);
		assertThat(auditadas(p.id())).isEmpty();
	}

	@Test
	void sinCabeceraCsrfNoSeLeeStorage() throws Exception {
		var p = publicar(atleta, "DNI_DEPORTISTA_FRENTE", "image/jpeg", bytes("JPEG-", 40));
		var r = descargar(atleta, p.id(), sesion, false);
		assertThat(r.statusCode()).isEqualTo(403);
		assertThat(codigo(r)).isEqualTo("CSRF_INVALIDO");
		assertThat(LECTURAS).hasValue(0);
		assertThat(auditadas(p.id())).isEmpty();
	}

	@Test
	void sinPermisoRespondeOcupadoYElPermisoSeLiberaTrasExitoYTrasError() throws Exception {
		var p = publicar(atleta, "DNI_DEPORTISTA_FRENTE", "image/jpeg", bytes("JPEG-", 40));
		var enLectura = new CountDownLatch(1);
		var liberar = new CountDownLatch(1);
		HOOK.set(() -> {
			enLectura.countDown();
			try { liberar.await(10, TimeUnit.SECONDS); }
			catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
		});
		var primera = CompletableFuture.supplyAsync(() -> {
			try { return descargar(atleta, p.id()); }
			catch (Exception ex) { throw new IllegalStateException(ex); }
		});
		assertThat(enLectura.await(10, TimeUnit.SECONDS)).isTrue();
		var ocupada = descargar(atleta, p.id());
		assertThat(ocupada.statusCode()).isEqualTo(429);
		assertThat(codigo(ocupada)).isEqualTo("DESCARGA_OCUPADA");
		sinContenido(ocupada, p);
		assertThat(LECTURAS).hasValue(1);
		HOOK.set(() -> {});
		liberar.countDown();
		assertThat(primera.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
		// Liberado tras el éxito.
		assertThat(descargar(atleta, p.id()).statusCode()).isEqualTo(200);
		// Liberado tras un error.
		ESTADO.set(500);
		assertThat(descargar(atleta, p.id()).statusCode()).isEqualTo(503);
		ESTADO.set(200);
		assertThat(descargar(atleta, p.id()).statusCode()).isEqualTo(200);
		assertThat(auditadas(p.id())).containsExactly("DOCUMENTO_DESCARGADO", "DOCUMENTO_DESCARGADO", "DOCUMENTO_DESCARGADO");
	}

	@Test
	void unClienteQueCortaLaConexionNoRetieneElPermiso() throws Exception {
		var p = publicar(atleta, "OTRO", "application/pdf", bytes("%PDF-GRANDE-SINTETICO-", 200_000));
		String[] x = csrf();
		try (var socket = new Socket("127.0.0.1", puerto)) {
			socket.getOutputStream().write(("GET " + ruta(atleta, p.id()) + " HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: " + x[0]
					+ "; BP_SESION=" + sesion + "\r\nX-XSRF-TOKEN: " + x[1] + "\r\nConnection: close\r\n\r\n")
					.getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().flush();
			InputStream in = socket.getInputStream();
			byte[] inicio = in.readNBytes(12);
			assertThat(new String(inicio, StandardCharsets.US_ASCII)).isEqualTo("HTTP/1.1 200");
		}
		// El servidor nota el corte al escribir; hasta entonces responde 429. Nunca queda retenido.
		long limite = System.nanoTime() + Duration.ofSeconds(15).toNanos();
		int estado;
		do {
			estado = descargar(atleta, p.id()).statusCode();
		} while (estado == 429 && System.nanoTime() < limite);
		assertThat(estado).isEqualTo(200);
	}

	@Test
	void laDescargaNoCreaArchivosEnElServidor() throws Exception {
		var p = publicar(atleta, "DNI_DEPORTISTA_FRENTE", "image/jpeg", bytes("JPEG-", 4000));
		List<String> antes = arbol(TOMCAT);
		assertThat(descargar(atleta, p.id()).statusCode()).isEqualTo(200);
		assertThat(arbol(TOMCAT)).isEqualTo(antes);
	}

	private static List<String> arbol(Path raiz) throws IOException {
		try (Stream<Path> archivos = Files.walk(raiz)) {
			return archivos.map(raiz::relativize).map(Path::toString).sorted().toList();
		}
	}
}
