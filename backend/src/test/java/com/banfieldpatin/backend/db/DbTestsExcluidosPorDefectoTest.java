package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * REQ-AUTH-18 S2: la suite por defecto no ejecuta pruebas con base de datos. Verifica la configuracion de surefire
 * en el pom y que toda prueba que abre un contexto con DataSource lleve la etiqueta "db".
 */
class DbTestsExcluidosPorDefectoTest {

	private static final Path RAIZ_TESTS = Path.of("src/test/java");

	private static Document pom() throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of("pom.xml").toFile());
	}

	private static Element hijo(Element padre, String nombre) {
		NodeList hijos = padre.getChildNodes();
		for (int i = 0; i < hijos.getLength(); i++) {
			Node n = hijos.item(i);
			if (n instanceof Element e && e.getTagName().equals(nombre)) {
				return e;
			}
		}
		return null;
	}

	private static Element surefire(Element plugins) {
		NodeList lista = plugins.getElementsByTagName("plugin");
		for (int i = 0; i < lista.getLength(); i++) {
			Element plugin = (Element) lista.item(i);
			Element artefacto = hijo(plugin, "artifactId");
			if (artefacto != null && artefacto.getTextContent().strip().equals("maven-surefire-plugin")) {
				return plugin;
			}
		}
		return null;
	}

	@Test
	void surefireExcluyeElGrupoDbPorDefecto() throws Exception {
		Element project = pom().getDocumentElement();
		Element plugins = hijo(hijo(project, "build"), "plugins");
		Element plugin = surefire(plugins);

		assertThat(plugin).as("plugin surefire en build/plugins").isNotNull();
		// La suite por defecto excluye las pruebas con base de datos y las opt-in contra ClamAV y Supabase Storage
		// reales (U7a/U7b): ninguna se ejecuta sin su perfil.
		List<String> excluidos = List.of(hijo(hijo(plugin, "configuration"), "excludedGroups").getTextContent().strip().split("\\s*,\\s*"));
		assertThat(excluidos).containsExactlyInAnyOrder("db", "clamav-real", "supabase-real");
	}

	@Test
	void cadaGrupoExcluidoSoloLoIncluyeSuPropioPerfil() throws Exception {
		Element project = pom().getDocumentElement();
		NodeList perfiles = hijo(project, "profiles").getElementsByTagName("profile");
		// Perfil -> unico grupo que incluye: db-tests (PostgreSQL), clamav-real (U7a) y supabase-real (U7b).
		java.util.Map<String, String> esperado = java.util.Map.of(
				"db-tests", "db", "clamav-real", "clamav-real", "supabase-real", "supabase-real");
		assertThat(perfiles.getLength()).isEqualTo(esperado.size());
		java.util.Set<String> vistos = new java.util.HashSet<>();
		for (int i = 0; i < perfiles.getLength(); i++) {
			Element perfil = (Element) perfiles.item(i);
			String id = hijo(perfil, "id").getTextContent().strip();
			assertThat(esperado).as("perfil inesperado: " + id).containsKey(id);
			vistos.add(id);
			Element configuracion = hijo(surefire(hijo(hijo(perfil, "build"), "plugins")), "configuration");
			assertThat(hijo(configuracion, "groups").getTextContent().strip()).as("grupo del perfil " + id).isEqualTo(esperado.get(id));
			// El perfil deja vacia la exclusion para que su grupo pueda ejecutarse.
			assertThat(hijo(configuracion, "excludedGroups").getTextContent().strip()).as("exclusion del perfil " + id).isEmpty();
		}
		assertThat(vistos).isEqualTo(esperado.keySet());
	}

	@Test
	void todaPruebaQueAbreUnContextoConBaseDeDatosEstaEtiquetadaDb() throws Exception {
		// Los marcadores se arman por partes para que este archivo no se detecte a si mismo.
		List<String> abrenBase = List.of("@Data" + "JpaTest", "@Spring" + "BootTest", "@Test" + "containers",
				"@Auto" + "ConfigureTestDatabase");
		try (Stream<Path> archivos = Files.walk(RAIZ_TESTS)) {
			List<String> sinEtiqueta = archivos.filter(p -> p.toString().endsWith(".java"))
					.filter(p -> !p.getFileName().toString().equals("PruebaDb.java"))
					.filter(p -> {
						String fuente = leer(p);
						boolean abre = abrenBase.stream().anyMatch(fuente::contains);
						boolean etiquetada = fuente.contains("@Tag(\"db\")") || fuente.contains("@PruebaDb");
						return abre && !etiquetada;
					})
					.map(Path::toString).toList();

			assertThat(sinEtiqueta).as("pruebas con base de datos sin @Tag(\"db\") ni @PruebaDb").isEmpty();
		}
	}

	@Test
	void existenPruebasDbParaV2ConsultasYConsumoAtomico() throws Exception {
		try (Stream<Path> archivos = Files.walk(RAIZ_TESTS)) {
			List<String> etiquetadas = archivos.filter(p -> p.toString().endsWith(".java"))
					.filter(p -> leer(p).contains("@PruebaDb\n") || leer(p).contains("@PruebaDb\r\n"))
					.map(p -> p.getFileName().toString()).sorted().toList();

			assertThat(etiquetadas).contains("V2ConstraintsDbTest.java", "ConsultasJpqlDbTest.java",
					"ConsumoAtomicoDbTest.java", "V3ConstraintsDbTest.java", "UsuarioMfaRepositoryDbTest.java",
					"MfaServiceDbTest.java");
		}
	}

	@Test
	void laPruebaDeFlujoCompletoDeMfaConAplicacionCompletaEstaEtiquetadaDb() throws Exception {
		String fuente = leer(RAIZ_TESTS.resolve("com/banfieldpatin/backend/db/MfaFlujoCompletoDbTest.java"));

		assertThat(fuente).contains("@Tag(\"db\")").contains("@Spring" + "BootTest");
	}

	private static String leer(Path p) {
		try {
			return Files.readString(p, StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
