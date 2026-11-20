package com.banfieldpatin.backend.documentos.descarga;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * D1: el contenido verificado vive sólo en memoria. Ni la descarga ni la lectura del puerto usan APIs
 * de archivos o temporales; la prueba HTTP con PostgreSQL comprueba además el directorio temporal.
 */
class DescargaSinDiscoTest {
	private static final Path RAIZ = Path.of("src", "main", "java", "com", "banfieldpatin", "backend", "documentos");
	private static final List<String> PROHIBIDOS = List.of("java.io.File", "java.nio.file", "FileOutputStream",
			"RandomAccessFile", "createTemp", "Files.", "transferTo(", "MappedByteBuffer");

	@Test
	void niLaDescargaNiLaLecturaDelPuertoEscribenEnDisco() throws Exception {
		List<Path> fuentes = new ArrayList<>();
		try (Stream<Path> descarga = Files.list(RAIZ.resolve("descarga"))) {
			descarga.forEach(fuentes::add);
		}
		fuentes.add(RAIZ.resolve("almacen").resolve("ContenidoLeido.java"));
		assertThat(fuentes).hasSizeGreaterThan(5);
		for (Path fuente : fuentes) {
			String codigo = Files.readString(fuente);
			assertThat(codigo).as(fuente.toString()).doesNotContain(PROHIBIDOS.toArray(String[]::new));
		}
		String adaptador = Files.readString(RAIZ.resolve("almacen").resolve("SupabaseAlmacenDocumentos.java"));
		String lectura = adaptador.substring(adaptador.indexOf("public ContenidoLeido leer("),
				adaptador.indexOf("private void verificarBucketPrivado()"));
		assertThat(lectura).doesNotContain(PROHIBIDOS.toArray(String[]::new));
	}
}
