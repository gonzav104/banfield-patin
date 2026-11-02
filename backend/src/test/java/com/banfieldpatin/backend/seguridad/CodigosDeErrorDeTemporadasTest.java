package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Coherencia de los codigos de error de la Fase 1 (temporadas y datos deportivos anuales), acotada a ese alcance: lo que
 * {@code OpenApiConfig.RUTAS} documenta para esas rutas existe en el codigo con el mismo estado HTTP y esta en la documentacion; y
 * lo que el codigo de esos modulos lanza esta documentado en alguna de esas rutas. Es una comprobacion estatica (lee fuentes y
 * documentos), sin contexto Spring ni base de datos. Los errores transversales (400 de validacion, 401, 403, 503) los agrega
 * OpenApiConfig a toda ruta y no se comparan aqui.
 */
class CodigosDeErrorDeTemporadasTest {

	private static final Path FUENTES = Path.of("src", "main", "java");
	private static final Path DOCS = Path.of("..", "docs");
	private static final Pattern LANZA = Pattern.compile("ExcepcionNegocio\\(\\s*HttpStatus\\.(\\w+),\\s*\"([A-Z_]+)\"");
	private static final Set<String> TRANSVERSALES = Set.of("VALIDACION", "SOLICITUD_INVALIDA", "NO_AUTENTICADO", "ACCESO_DENEGADO",
			"CSRF_INVALIDO", "SERVICIO_NO_DISPONIBLE");

	/** Las rutas de la Fase 1: todas las que pasan por {@code /temporadas}. */
	private static Map<String, Set<String>> documentadasPorCodigo() {
		Map<String, Set<String>> codigos = new TreeMap<>();
		OpenApiConfig.RUTAS.forEach((ruta, datos) -> {
			if (ruta.contains("/temporadas")) {
				for (String error : datos.errores()) {
					codigos.computeIfAbsent(error.substring(4), c -> new TreeSet<>()).add(error.substring(0, 3));
				}
			}
		});
		return codigos;
	}

	private static String leerTodo(Path raiz) throws IOException {
		try (Stream<Path> archivos = Files.walk(raiz)) {
			StringBuilder todo = new StringBuilder();
			for (Path archivo : archivos.filter(Files::isRegularFile).filter(a -> a.toString().endsWith(".java")).toList()) {
				todo.append(Files.readString(archivo)).append('\n');
			}
			return todo.toString();
		}
	}

	private static Map<String, Set<String>> lanzados(String fuente) {
		Map<String, Set<String>> codigos = new TreeMap<>();
		Matcher m = LANZA.matcher(fuente);
		while (m.find()) {
			codigos.computeIfAbsent(m.group(2), c -> new TreeSet<>()).add(String.valueOf(HttpStatus.valueOf(m.group(1)).value()));
		}
		return codigos;
	}

	@Test
	void hayRutasDeLaFase1ConCodigosPropiosDocumentados() {
		Map<String, Set<String>> documentados = documentadasPorCodigo();

		assertThat(OpenApiConfig.RUTAS.keySet().stream().filter(r -> r.contains("/temporadas"))).hasSizeGreaterThanOrEqualTo(11);
		assertThat(documentados.keySet()).contains("TEMPORADA_NO_ENCONTRADA", "TEMPORADA_ANIO_DUPLICADO",
				"DEPORTISTA_TEMPORADA_NO_ENCONTRADA", "DEPORTISTA_TEMPORADA_DUPLICADA", "NUMERO_CORREDOR_DUPLICADO",
				"DEPORTISTA_INACTIVO", "CONFLICTO_CONCURRENCIA");
	}

	@Test
	void cadaCodigoDocumentadoExisteEnElCodigoConElMismoEstadoYEstaEnLaDocumentacion() throws Exception {
		String fuente = leerTodo(FUENTES);
		Map<String, Set<String>> lanzados = lanzados(fuente);
		String docs = Files.readString(DOCS.resolve("TEMPORADAS.md")) + Files.readString(DOCS.resolve("FAMILIAS-DEPORTISTAS.md"));

		documentadasPorCodigo().forEach((codigo, estados) -> {
			assertThat(fuente).as("el codigo %s documentado no existe en el codigo", codigo).contains("\"" + codigo + "\"");
			assertThat(docs).as("el codigo %s no figura en docs/TEMPORADAS.md ni en docs/FAMILIAS-DEPORTISTAS.md", codigo)
					.contains(codigo);
			if (lanzados.containsKey(codigo)) {
				assertThat(lanzados.get(codigo)).as("estado HTTP con el que se lanza %s", codigo).containsAll(estados);
			}
		});
	}

	@Test
	void cadaCodigoQueLanzanLosModulosDeLaFase1EstaDocumentadoEnAlgunaRutaConSuEstado() throws Exception {
		Map<String, Set<String>> documentados = documentadasPorCodigo();
		String fuenteFase1 = leerTodo(FUENTES.resolve(Path.of("com", "banfieldpatin", "backend", "temporadas"))) + "\n"
				+ Files.readString(FUENTES.resolve(Path.of("com", "banfieldpatin", "backend", "familias", "portal",
						"FamiliaPortalTemporadasService.java")));

		lanzados(fuenteFase1).forEach((codigo, estados) -> {
			if (!TRANSVERSALES.contains(codigo)) {
				assertThat(documentados).as("el codigo %s se lanza en la Fase 1 pero ninguna ruta lo documenta", codigo)
						.containsKey(codigo);
				assertThat(documentados.get(codigo)).as("estado HTTP documentado de %s", codigo).containsAll(estados);
			}
		});
	}
}
