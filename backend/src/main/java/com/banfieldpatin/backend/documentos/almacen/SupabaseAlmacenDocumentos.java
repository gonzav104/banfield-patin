package com.banfieldpatin.backend.documentos.almacen;

import static com.banfieldpatin.backend.documentos.almacen.FalloAlmacenDocumentos.Codigo.*;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Adaptador interno sin endpoints, transacciones DB, URLs públicas ni borrados por ruta arbitraria. */
public final class SupabaseAlmacenDocumentos implements AlmacenDocumentos {
	private static final int MAX_RESPUESTA = 16_384;
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final StoragePropiedades propiedades;
	private final RestClient cliente;

	public SupabaseAlmacenDocumentos(StoragePropiedades propiedades) {
		this.propiedades = propiedades;
		var http = HttpClient.newBuilder().connectTimeout(propiedades.conexion())
				.followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
		var fabrica = new JdkClientHttpRequestFactory(http);
		fabrica.setReadTimeout(propiedades.respuesta());
		// Builder propio: no heredar interceptores de trazas/cabeceras de otros clientes.
		this.cliente = RestClient.builder().requestFactory(fabrica).baseUrl(propiedades.url() + "/storage/v1")
				.defaultHeader("Authorization", "Bearer " + propiedades.credencial())
				.defaultHeader("apikey", propiedades.credencial()).build();
	}

    @Override
    public DestinoPreparado prepararDestino() {
        return new DestinoPreparado(propiedades.bucket(), UUID.randomUUID());
    }

    @Override
    public void crear(DestinoPreparado destino, java.io.InputStream contenido, long tamanioBytes, String tipoContenido) {
        validarDestino(destino);
        MediaType mime = validarMime(tipoContenido);
        if (contenido == null || tamanioBytes <= 0) throw fallo(ENTRADA_INVALIDA, false);
        verificarBucketPrivado();
        var alta = cliente.post().uri("/object/{bucket}/{clave}", destino.bucketInterno(), destino.objetoIdInterno())
                .header("x-upsert", "false").contentType(mime).contentLength(tamanioBytes)
                .body(salida -> {
                    byte[] bloque = new byte[8192];
                    long restantes = tamanioBytes;
                    while (restantes > 0) {
                        int n = contenido.read(bloque, 0, (int) Math.min(bloque.length, restantes));
                        if (n < 0) throw new java.io.IOException("Contenido incompleto");
                        if (n == 0) {
                            int b = contenido.read();
                            if (b < 0) throw new java.io.IOException("Contenido incompleto");
                            salida.write(b); restantes--;
                        } else { salida.write(bloque, 0, n); restantes -= n; }
                    }
                    if (contenido.read() != -1) throw new java.io.IOException("Contenido excedente");
                });
        confirmarAlta(ejecutar(alta, false), destino.objetoIdInterno());
    }

    @Override
    public InspeccionAlmacen inspeccionar(DestinoPreparado destino) {
        validarDestino(destino);
        long inicio = System.nanoTime();
        try {
            verificarBucketPrivado();
            return cliente.head().uri("/object/authenticated/{bucket}/{clave}", destino.bucketInterno(), destino.objetoIdInterno())
                    .exchange((req, res) -> {
                        int estado = res.getStatusCode().value();
                        if (estado == 404) return InspeccionAlmacen.ausente();
                        if (estado == 401 || estado == 403) return InspeccionAlmacen.incierta(AUTENTICACION);
                        if (estado != 200) return InspeccionAlmacen.incierta(estado >= 400 && estado < 500 ? RECHAZADO : RESPUESTA_INESPERADA);
                        var longitudes = res.getHeaders().get("Content-Length");
                        var tipos = res.getHeaders().get("Content-Type");
                        if (longitudes == null || longitudes.size() != 1 || tipos == null || tipos.size() != 1
                                || !longitudes.getFirst().matches("[1-9][0-9]{0,18}") || tipos.getFirst().length() > 1000)
                            return InspeccionAlmacen.incierta(RESPUESTA_INESPERADA);
                        try {
                            long tamanio = Long.parseLong(longitudes.getFirst());
                            String mime = validarMime(tipos.getFirst()).toString();
                            return InspeccionAlmacen.existe(tamanio, mime);
                        } catch (IllegalArgumentException | FalloAlmacenDocumentos ex) {
                            return InspeccionAlmacen.incierta(RESPUESTA_INESPERADA);
                        }
                    });
        } catch (FalloAlmacenDocumentos ex) {
            return InspeccionAlmacen.incierta(ex.codigo());
        } catch (ResourceAccessException ex) {
            return InspeccionAlmacen.incierta(errorRed(ex, inicio).codigo());
        } catch (RuntimeException ex) {
            return InspeccionAlmacen.incierta(RESPUESTA_INESPERADA);
        }
    }

    private void validarDestino(DestinoPreparado destino) {
        if (destino == null || !propiedades.bucket().equals(destino.bucketInterno())) throw fallo(ENTRADA_INVALIDA, false);
    }

    private static MediaType validarMime(String tipoContenido) {
        try {
            MediaType mime = MediaType.parseMediaType(tipoContenido);
            if (!mime.isConcrete() || !mime.getParameters().isEmpty() || tipoContenido.chars().anyMatch(c -> c < 32 || c == 127))
                throw new IllegalArgumentException();
            return mime;
        } catch (IllegalArgumentException ex) { throw fallo(ENTRADA_INVALIDA, false); }
    }

    private void confirmarAlta(JsonNode confirmacion, UUID clave) {
        if (confirmacion == null || !confirmacion.isObject() || !confirmacion.path("Key").asText().equals(propiedades.bucket() + "/" + clave))
            throw fallo(RESPUESTA_INESPERADA, true);
    }

	@Override
	public Creado crear(byte[] contenido, String tipoContenido) {
		MediaType mime = validarMime(tipoContenido);
		if (contenido == null || contenido.length == 0) throw fallo(ENTRADA_INVALIDA, false);
		verificarBucketPrivado();
		UUID clave = UUID.randomUUID();
		var alta = cliente.post().uri("/object/{bucket}/{clave}", propiedades.bucket(), clave)
				.header("x-upsert", "false").contentType(mime).body(contenido);
		JsonNode confirmacion = ejecutar(alta, false);
		confirmarAlta(confirmacion, clave);
		return new Creado(clave, this);
	}

	@Override
	public void eliminarCreado(Creado creado) {
		if (creado == null || !creado.reclamar(this)) throw fallo(ENTRADA_INVALIDA, false);
		// Consume el recibo incluso ante fallo: no presupone que sea seguro reintentar/reconciliar.
		String cuerpo = "{\"prefixes\":[\"" + creado.claveInterna() + "\"]}";
		var baja = cliente.method(org.springframework.http.HttpMethod.DELETE).uri("/object/{bucket}", propiedades.bucket())
				.contentType(MediaType.APPLICATION_JSON).body(cuerpo.getBytes(StandardCharsets.UTF_8));
		JsonNode confirmacion = ejecutar(baja, true);
		if (confirmacion == null || !confirmacion.isArray() || confirmacion.size() != 1
				|| !confirmacion.get(0).path("name").asText().equals(creado.claveInterna().toString())) {
			throw fallo(RESPUESTA_INESPERADA, true);
		}
	}

	private void verificarBucketPrivado() {
		JsonNode bucket;
		try {
			bucket = ejecutar(cliente.get().uri("/bucket/{bucket}", propiedades.bucket()), false);
		} catch (FalloAlmacenDocumentos ex) {
			// Ningún POST se ha iniciado: un fallo en esta lectura no deja un objeto nuevo incierto.
			throw fallo(ex.codigo(), false);
		}
		if (bucket == null || !bucket.path("public").isBoolean() || bucket.path("public").asBoolean()) {
			throw fallo(RECHAZADO, false);
		}
	}

	private JsonNode ejecutar(RestClient.RequestHeadersSpec<?> peticion, boolean borrado) {
		long inicio = System.nanoTime();
		try {
			return peticion.exchange((req, res) -> {
				int estado = res.getStatusCode().value();
				if (estado == 401 || estado == 403) throw fallo(AUTENTICACION, borrado);
				if (estado == 409) throw fallo(CONFLICTO, borrado);
				if (estado >= 400 && estado < 500) throw fallo(RECHAZADO, borrado);
				if (estado != 200) throw fallo(RESPUESTA_INESPERADA, true);
				byte[] bytes = res.getBody().readNBytes(MAX_RESPUESTA + 1);
				if (bytes.length > MAX_RESPUESTA) throw fallo(RESPUESTA_INESPERADA, true);
				try { return JSON.readTree(bytes); }
				catch (RuntimeException ex) { throw fallo(RESPUESTA_INESPERADA, true); }
			});
        } catch (ResourceAccessException ex) {
            throw errorRed(ex, inicio);
        }
    }

    private FalloAlmacenDocumentos errorRed(ResourceAccessException ex, long inicio) {
        boolean timeout = System.nanoTime() - inicio >= propiedades.respuesta().toNanos();
        for (Throwable causa = ex; causa != null; causa = causa.getCause()) {
            if (causa instanceof HttpTimeoutException || causa instanceof SocketTimeoutException) timeout = true;
        }
        return fallo(timeout ? TIMEOUT : RED, true);
    }

	private static FalloAlmacenDocumentos fallo(FalloAlmacenDocumentos.Codigo codigo, boolean incierto) {
		return new FalloAlmacenDocumentos(codigo, incierto);
	}
}
