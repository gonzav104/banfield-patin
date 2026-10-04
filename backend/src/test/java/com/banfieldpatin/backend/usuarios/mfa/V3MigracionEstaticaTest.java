package com.banfieldpatin.backend.usuarios.mfa;

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

import org.junit.jupiter.api.Test;

/**
 * Verificacion estatica (sin base de datos) de V3. V1 y V2 ya estan aplicadas en la base compartida y son
 * inmutables: este test fija el SHA-256 de ambas y exige que V3 sea aditiva. No ejecuta SQL.
 */
class V3MigracionEstaticaTest {

	private static final Path MIGRACIONES = Path.of("src/main/resources/db/migration");
	private static final String V3 = "V3__crear_usuario_mfa.sql";
	private static final String SHA256_V1 = "fa041142c2e983d929c912a55a8136c000180677d14eab4fe685efed9fb9bd26";
	private static final String SHA256_V2 = "77afca70b0244d936fd998074ab74c8517b231e393caed3dcfcc014323b9ba01";

	private static String leer(String nombre) throws IOException {
		return Files.readString(MIGRACIONES.resolve(nombre), StandardCharsets.UTF_8);
	}

	private static String sinComentarios(String sql) {
		return sql.lines().map(l -> l.replaceFirst("--.*$", "")).collect(Collectors.joining("\n"));
	}

	private static String sha256(String nombre) throws Exception {
		byte[] bytes = Files.readAllBytes(MIGRACIONES.resolve(nombre));
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	@Test
	void v1YV2PermanecenIdenticasALasAplicadas() throws Exception {
		assertThat(sha256("V1__crear_esquema_inicial.sql")).isEqualTo(SHA256_V1);
		assertThat(sha256("V2__crear_invitacion.sql")).isEqualTo(SHA256_V2);
	}

	@Test
	void v3EsAditivaYNoManejaTransacciones() throws Exception {
		// UPDATE solo se permite dentro de "BEFORE UPDATE ON" del trigger.
		String sql = sinComentarios(leer(V3)).toUpperCase().replace("BEFORE UPDATE ON", "");
		for (String prohibida : List.of("DROP ", "ALTER ", "TRUNCATE", "DELETE ", "UPDATE ", "INSERT ", "BEGIN",
				"COMMIT", "ROLLBACK", "GRANT ", "REVOKE ", "CREATE OR REPLACE", "CREATE SCHEMA",
				"CREATE EXTENSION")) {
			assertThat(sql).as("V3 no debe contener '%s'", prohibida).doesNotContain(prohibida);
		}
	}

	@Test
	void todosLosObjetosEstanCalificadosConElEsquema() throws Exception {
		String sql = sinComentarios(leer(V3));
		assertThat(sql).doesNotContain("public.");
		assertThat(Pattern.compile("CREATE TABLE (\\S+)").matcher(sql).results().map(m -> m.group(1)).toList())
				.containsExactly("gestion_patin.usuario_mfa");
		assertThat(Pattern.compile("REFERENCES\\s+(\\S+?)\\(").matcher(sql).results().map(m -> m.group(1)).toList())
				.containsExactlyInAnyOrder("gestion_patin.escuela", "gestion_patin.usuario");
		assertThat(sql).contains("BEFORE UPDATE ON gestion_patin.usuario_mfa");
		assertThat(sql).contains("EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion()");
	}

	@Test
	void v3DefineLaTablaConLasRestriccionesRequeridas() throws Exception {
		String sql = sinComentarios(leer(V3));
		assertThat(sql).contains(
				"usuario_id          uuid PRIMARY KEY", "escuela_id          uuid NOT NULL",
				"secreto_cifrado     bytea NOT NULL", "confirmado_en       timestamptz,",
				"ultimo_paso_usado   bigint,", "ck_usuario_mfa_secreto_longitud",
				"ck_usuario_mfa_paso_no_negativo", "ck_usuario_mfa_paso_solo_si_confirmado",
				"FOREIGN KEY (usuario_id, escuela_id)", "REFERENCES gestion_patin.usuario(id, escuela_id)",
				"REFERENCES gestion_patin.escuela(id)");
	}

	@Test
	void elSecretoNoTieneColumnaEnClaro() throws Exception {
		String sql = sinComentarios(leer(V3));
		assertThat(sql).doesNotContainPattern("(?m)^\\s+secreto\\s");
		assertThat(sql).doesNotContainPattern("(?m)^\\s+secreto_base32");
		assertThat(sql).doesNotContainPattern("(?m)^\\s+codigo");
	}

	@Test
	void lasReferenciasDeV3ExistenEnV1() throws Exception {
		String v1 = leer("V1__crear_esquema_inicial.sql");
		for (String objeto : List.of("uq_usuario_id_escuela UNIQUE (id, escuela_id)",
				"mfa_habilitado      boolean NOT NULL DEFAULT false",
				"CREATE OR REPLACE FUNCTION gestion_patin.actualizar_timestamp_modificacion()")) {
			assertThat(v1).contains(objeto);
		}
	}
}
