package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sin contexto Spring ni base de datos: la tabla de rutas de docs/FAMILIAS-DEPORTISTAS.md es el resumen legible del
 * contrato (la fuente de verdad es la especificacion OpenAPI desde RNF-14), y esta prueba impide que se desactualice
 * (REQ-OAS-02). Cada mapeo de los
 * controladores de {@code familias} (salvo las invitaciones, documentadas en SEGURIDAD.md) y {@code deportistas}, incluido
 * el portal, debe figurar en la tabla, y la tabla no puede nombrar rutas que no existen. El descubrimiento de rutas usa el
 * mismo mecanismo que InventarioRutasTest (escaneo de {@code @RestController} y sus {@code @RequestMapping}).
 */
class RutasDocumentadasTest {

	private static final Path DOCUMENTO = Path.of("..", "docs", "FAMILIAS-DEPORTISTAS.md");
	private static final String BASE = "com.banfieldpatin.backend";
	private static final Pattern RUTA_EN_TABLA = Pattern.compile("`(GET|POST|PUT|PATCH|DELETE) (/api/[^`\\s]+)`");

	/** Los controladores que documenta ese archivo: familias (sin invitaciones) y deportistas. */
	private static boolean esDelModulo(String paquete) {
		return paquete.startsWith(BASE + ".deportistas")
				|| (paquete.startsWith(BASE + ".familias") && !paquete.startsWith(BASE + ".familias.invitaciones"));
	}

	private static Set<String> rutasDelModulo() throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider escaner = new ClassPathScanningCandidateComponentProvider(false);
		escaner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
		Set<String> rutas = new TreeSet<>();
		for (BeanDefinition definicion : escaner.findCandidateComponents(BASE)) {
			Class<?> tipo = Class.forName(definicion.getBeanClassName());
			if (!esDelModulo(tipo.getPackageName())) {
				continue;
			}
			RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(tipo, RequestMapping.class);
			String prefijo = base == null || base.path().length == 0 ? "" : base.path()[0];
			for (Method metodo : tipo.getDeclaredMethods()) {
				RequestMapping mapeo = AnnotatedElementUtils.findMergedAnnotation(metodo, RequestMapping.class);
				if (mapeo == null) {
					continue;
				}
				String sufijo = mapeo.path().length == 0 ? "" : mapeo.path()[0];
				for (RequestMethod verbo : mapeo.method()) {
					rutas.add(verbo + " " + prefijo + sufijo);
				}
			}
		}
		return rutas;
	}

	/** Rutas con formato "METODO /ruta" de la seccion "Tabla de rutas" (hasta el siguiente encabezado de nivel 2). */
	static Set<String> rutasDeLaTabla() throws IOException {
		String texto = Files.readString(DOCUMENTO);
		int inicio = texto.indexOf("## 2. Tabla de rutas");
		assertThat(inicio).as("el documento debe tener la seccion '## 2. Tabla de rutas'").isGreaterThanOrEqualTo(0);
		int fin = texto.indexOf("\n## ", inicio + 5);
		String seccion = texto.substring(inicio, fin < 0 ? texto.length() : fin);
		Set<String> rutas = new TreeSet<>();
		// Solo la primera columna de cada fila (las demas columnas pueden mencionar rutas de pasada).
		for (String linea : seccion.split("\n")) {
			if (!linea.startsWith("| `")) {
				continue;
			}
			Matcher m = RUTA_EN_TABLA.matcher(linea.substring(0, linea.indexOf("|", 2)));
			if (m.find()) {
				rutas.add(m.group(1) + " " + m.group(2));
			}
		}
		return rutas;
	}

	@Test
	void cadaRutaDeFamiliasDeportistasYPortalFiguraEnLaTablaDeLaDocumentacion() throws Exception {
		Set<String> documentadas = rutasDeLaTabla();

		assertThat(rutasDelModulo()).as("rutas del codigo ausentes en docs/FAMILIAS-DEPORTISTAS.md")
				.isNotEmpty().allSatisfy(ruta -> assertThat(documentadas).contains(ruta));
	}

	@Test
	void laTablaNoNombraRutasQueYaNoExisten() throws Exception {
		assertThat(rutasDeLaTabla()).as("rutas de la tabla que no existen en el codigo")
				.containsExactlyInAnyOrderElementsOf(rutasDelModulo());
	}

	@Test
	void ningunaRutaDocumentadaEscribeBajoElPortalDeFamilia() throws Exception {
		assertThat(rutasDeLaTabla().stream().filter(r -> r.contains(" /api/familia/") || r.endsWith(" /api/familia")))
				.containsExactlyInAnyOrder("GET /api/familia/mi-familia", "GET /api/familia/deportistas",
						"GET /api/familia/deportistas/{id}");
	}

	@Test
	void laDocumentacionDeclaraRnf14CumplidoConLaPoliticaDeExposicion() throws Exception {
		// Slice 7: RNF-14 pasa a CUMPLIDO porque OpenApiRutasTest, OpenApiValidezTest y las pruebas de exposicion pasan en el
		// build; si alguna dejara de existir o se deshabilitara, esta declaracion no debe quedar sin respaldo (revisar docs).
		String texto = Files.readString(DOCUMENTO);

		assertThat(texto).contains("RNF-14 está CUMPLIDO").contains("no exige Swagger UI").contains("/v3/api-docs");
		assertThat(texto).doesNotContain("RNF-14 está PENDIENTE");
		assertThat(Files.readString(Path.of("..", "docs", "SEGURIDAD.md"))).contains("`/v3/api-docs`").contains("perfil `dev`")
				.contains("springdoc.api-docs.enabled=false");
	}
}
