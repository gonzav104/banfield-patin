package com.banfieldpatin.backend.familias.invitaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Verificacion estatica (sin base de datos) de las migraciones. No ejecuta SQL: V2 solo puede llegar a la base
 * compartida tras una revision humana, y este test impide que V1 (ya aplicada) cambie o que V2 deje de ser aditiva.
 */
class V2MigracionEstaticaTest {

	private static final Path MIGRACIONES = Path.of("src/main/resources/db/migration");
	/** SHA-256 de V1__crear_esquema_inicial.sql tal como esta aplicada: debe permanecer identico. */
	private static final String SHA256_V1 = "fa041142c2e983d929c912a55a8136c000180677d14eab4fe685efed9fb9bd26";

	private static String leer(String nombre) throws IOException {
		return Files.readString(MIGRACIONES.resolve(nombre), StandardCharsets.UTF_8);
	}

	/** Quita comentarios de linea para que el texto explicativo no dispare las verificaciones. */
	private static String sinComentarios(String sql) {
		return sql.lines().map(l -> l.replaceFirst("--.*$", "")).collect(Collectors.joining("\n"));
	}

	@Test
	void v1PermaneceIdenticaALaAplicada() throws Exception {
		byte[] bytes = Files.readAllBytes(MIGRACIONES.resolve("V1__crear_esquema_inicial.sql"));
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		assertThat(hash).isEqualTo(SHA256_V1);
	}

	@Test
	void elDirectorioSoloContieneDeV1AV4() throws Exception {
		try (Stream<Path> archivos = Files.list(MIGRACIONES)) {
			assertThat(archivos.map(p -> p.getFileName().toString()).sorted().toList())
					.containsExactly("V1__crear_esquema_inicial.sql", "V2__crear_invitacion.sql",
							"V3__crear_usuario_mfa.sql", "V4__restricciones_familia_deportista.sql");
		}
	}

	@Test
	void v2EsAditivaYNoManejaTransacciones() throws Exception {
		// UPDATE solo se permite dentro de "BEFORE UPDATE ON" del trigger.
		String sql = sinComentarios(leer("V2__crear_invitacion.sql")).toUpperCase().replace("BEFORE UPDATE ON", "");
		for (String prohibida : List.of("DROP ", "ALTER ", "TRUNCATE", "DELETE ", "UPDATE ", "INSERT ", "BEGIN",
				"COMMIT", "ROLLBACK", "GRANT ", "REVOKE ", "CREATE OR REPLACE", "CREATE SCHEMA",
				"CREATE EXTENSION")) {
			assertThat(sql).as("V2 no debe contener '%s'", prohibida).doesNotContain(prohibida);
		}
	}

	@Test
	void todosLosObjetosEstanCalificadosConElEsquema() throws Exception {
		String sql = sinComentarios(leer("V2__crear_invitacion.sql"));
		assertThat(sql).doesNotContain("public.");
		assertThat(Pattern.compile("CREATE TABLE (\\S+)").matcher(sql).results().map(m -> m.group(1)).toList())
				.containsExactly("gestion_patin.invitacion");
		assertThat(Pattern.compile("REFERENCES\\s+(\\S+?)\\(").matcher(sql).results().map(m -> m.group(1)).toList())
				.isNotEmpty()
				.allSatisfy(t -> assertThat(t).startsWith("gestion_patin."));
		assertThat(Pattern.compile("(?m)^\\s*ON (\\S+)").matcher(sql).results().map(m -> m.group(1)).toList())
				.hasSize(4)
				.allSatisfy(t -> assertThat(t).startsWith("gestion_patin."));
		assertThat(sql).contains("BEFORE UPDATE ON gestion_patin.invitacion");
	}

	@Test
	void v2DefineChecksFkCompuestasEIndicesRequeridos() throws Exception {
		String sql = sinComentarios(leer("V2__crear_invitacion.sql"));
		assertThat(sql).contains(
				"ck_invitacion_token_hash_formato", "ck_invitacion_expiracion",
				"(usado_en IS NULL) = (usuario_id IS NULL)", "(revocada_en IS NULL) = (revocada_por IS NULL)",
				"usado_en IS NULL OR revocada_en IS NULL", "uq_invitacion_id_escuela",
				"FOREIGN KEY (familia_id, escuela_id)", "REFERENCES gestion_patin.familia(id, escuela_id)",
				"FOREIGN KEY (deportista_id, escuela_id)", "REFERENCES gestion_patin.deportista(id, escuela_id)",
				"FOREIGN KEY (creada_por, escuela_id)", "FOREIGN KEY (usuario_id, escuela_id)",
				"FOREIGN KEY (revocada_por, escuela_id)", "REFERENCES gestion_patin.usuario(id, escuela_id)",
				"CREATE UNIQUE INDEX uq_invitacion_token_hash", "WHERE usuario_id IS NOT NULL",
				"ix_invitacion_escuela_fecha", "ix_invitacion_familia",
				"gestion_patin.actualizar_timestamp_modificacion()");
	}

	@Test
	void elTokenSoloSeAlmacenaComoHashYNoHayColumnaDeEstadoNiDeTokenEnClaro() throws Exception {
		String sql = sinComentarios(leer("V2__crear_invitacion.sql"));
		assertThat(sql).containsPattern("(?m)^\\s+token_hash\\s+varchar\\(64\\) NOT NULL,");
		assertThat(sql).doesNotContainPattern("(?m)^\\s+token\\s");
		assertThat(sql).doesNotContainPattern("(?m)^\\s+estado\\s");
		// deportista_id es nullable (extension RF-04): sin NOT NULL.
		assertThat(sql).containsPattern("(?m)^\\s+deportista_id\\s+uuid,");
	}

	@Test
	void lasReferenciasDeV2ExistenEnV1() throws Exception {
		String v1 = leer("V1__crear_esquema_inicial.sql");
		for (String objeto : List.of("uq_familia_id_escuela UNIQUE (id, escuela_id)",
				"uq_usuario_id_escuela UNIQUE (id, escuela_id)", "uq_deportista_id_escuela UNIQUE (id, escuela_id)",
				"CREATE OR REPLACE FUNCTION gestion_patin.actualizar_timestamp_modificacion()")) {
			assertThat(v1).contains(objeto);
		}
	}
}
