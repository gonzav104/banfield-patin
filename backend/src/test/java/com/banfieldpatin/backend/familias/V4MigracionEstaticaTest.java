package com.banfieldpatin.backend.familias;

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
 * Verificacion estatica (sin base de datos) de V4. No ejecuta SQL: V4 solo llega a la base compartida (Supabase)
 * tras la revision humana de la compuerta G1, y este test impide que V1-V3 (ya aplicadas) cambien y que V4 deje de
 * ser aditiva.
 *
 * <p>Compuerta G2 cerrada: V4 se aplico en Supabase el 2026-10-05 (Flyway: version 4 con exito, checksum de Flyway
 * 2126611739) y desde ese momento es inmutable; su SHA-256 queda fijado abajo y cualquier correccion va en una V5.
 */
class V4MigracionEstaticaTest {

	private static final Path MIGRACIONES = Path.of("src/main/resources/db/migration");
	private static final String V1 = "V1__crear_esquema_inicial.sql";
	private static final String V2 = "V2__crear_invitacion.sql";
	private static final String V3 = "V3__crear_usuario_mfa.sql";
	private static final String V4 = "V4__restricciones_familia_deportista.sql";

	private static final String SHA256_V1 = "fa041142c2e983d929c912a55a8136c000180677d14eab4fe685efed9fb9bd26";
	private static final String SHA256_V2 = "77afca70b0244d936fd998074ab74c8517b231e393caed3dcfcc014323b9ba01";
	private static final String SHA256_V3 = "19933dcd6529b95e19a5ca525386d5d0915a783b39fc786031583720e639e1fe";
	private static final String SHA256_V4 = "8e449b9f812c2d354d08f1f73eb026bada353e1b173a69368c50d5460d43ba48";

	private static String leer(String nombre) throws IOException {
		return Files.readString(MIGRACIONES.resolve(nombre), StandardCharsets.UTF_8);
	}

	private static String sha256(String nombre) throws Exception {
		byte[] bytes = Files.readAllBytes(MIGRACIONES.resolve(nombre));
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	/** Quita comentarios de linea para que el texto explicativo no dispare las verificaciones. */
	private static String sinComentarios(String sql) {
		return sql.lines().map(l -> l.replaceFirst("--.*$", "")).collect(Collectors.joining("\n"));
	}

	@Test
	void v1V2YV3PermanecenIdenticasALasAplicadas() throws Exception {
		assertThat(sha256(V1)).isEqualTo(SHA256_V1);
		assertThat(sha256(V2)).isEqualTo(SHA256_V2);
		assertThat(sha256(V3)).isEqualTo(SHA256_V3);
	}

	@Test
	void v4PermaneceIdenticaALaAplicadaEnSupabase() throws Exception {
		assertThat(sha256(V4)).isEqualTo(SHA256_V4);
	}

	@Test
	void v4EsAditivaYNoManejaTransacciones() throws Exception {
		String sql = sinComentarios(leer(V4)).toUpperCase();
		for (String prohibida : List.of("DROP", "TRUNCATE", "DELETE", "UPDATE", "INSERT", "BEGIN", "COMMIT",
				"ROLLBACK", "GRANT", "REVOKE", "CREATE OR REPLACE", "CREATE TABLE", "CONCURRENTLY")) {
			assertThat(sql).as("V4 no debe contener '%s'", prohibida).doesNotContain(prohibida);
		}
		assertThat(sinComentarios(leer(V4))).doesNotContain("public.");
	}

	@Test
	void laUnicaSentenciaAlterEsAddConstraintSobreFamiliaDeportista() throws Exception {
		String sql = sinComentarios(leer(V4));
		var alters = Pattern.compile("(?is)ALTER\\s+TABLE\\s+(\\S+)\\s+(ADD\\s+CONSTRAINT)\\s+(\\S+)").matcher(sql)
				.results().toList();
		assertThat(alters).hasSize(1);
		assertThat(alters.get(0).group(1)).isEqualTo("gestion_patin.familia_deportista");
		assertThat(alters.get(0).group(3)).isEqualTo("ck_fd_activo_autorizado");
		// Ninguna otra forma de ALTER (otra tabla, DROP/ALTER COLUMN, etc.).
		assertThat(Pattern.compile("(?i)\\bALTER\\b").matcher(sql).results().count()).isEqualTo(1);
	}

	@Test
	void creaExactamenteLosTresObjetosAprobadosCalificadosConElEsquema() throws Exception {
		String sql = sinComentarios(leer(V4));

		var indices = Pattern.compile("(?is)CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(\\S+)\\s+ON\\s+(\\S+)").matcher(sql)
				.results().toList();
		assertThat(indices).extracting(m -> m.group(2)).containsExactly("ix_tutor_familia", "uq_fd_principal_activo");
		assertThat(indices).extracting(m -> m.group(3)).containsExactly("gestion_patin.tutor",
				"gestion_patin.familia_deportista");
		assertThat(Pattern.compile("(?i)\\bCREATE\\b").matcher(sql).results().count()).isEqualTo(2);

		assertThat(sql).containsPattern("(?s)CREATE INDEX ix_tutor_familia\\s+ON gestion_patin\\.tutor \\(escuela_id, familia_id\\);");
		assertThat(sql).containsPattern("(?s)ADD CONSTRAINT ck_fd_activo_autorizado\\s+CHECK \\(estado <> 'ACTIVO' OR "
				+ "\\(autorizado_por IS NOT NULL AND autorizado_en IS NOT NULL\\)\\);");
		assertThat(sql).containsPattern("(?s)CREATE UNIQUE INDEX uq_fd_principal_activo\\s+"
				+ "ON gestion_patin\\.familia_deportista \\(deportista_id\\)\\s+WHERE estado = 'ACTIVO' AND es_principal;");
		// Sin CHECK de formato de DNI/CUIL (se valida en la aplicacion).
		assertThat(sql.toLowerCase()).doesNotContain("dni").doesNotContain("cuil");
	}

	@Test
	void v4NoReutilizaNombresDeObjetosDeV1V2V3() throws Exception {
		for (String objeto : List.of("ix_tutor_familia", "ck_fd_activo_autorizado", "uq_fd_principal_activo")) {
			for (String previa : List.of(V1, V2, V3)) {
				assertThat(leer(previa)).as("%s ya existe en %s", objeto, previa).doesNotContain(objeto);
			}
		}
	}

	@Test
	void lasColumnasYLosLiteralesQueV4ReferenciaExistenEnV1() throws Exception {
		String v1 = leer(V1);
		String tutor = bloqueTabla(v1, "tutor");
		String vinculo = bloqueTabla(v1, "familia_deportista");

		assertThat(tutor).containsPattern("(?m)^\\s+escuela_id\\s+uuid NOT NULL,");
		assertThat(tutor).containsPattern("(?m)^\\s+familia_id\\s+uuid NOT NULL,");
		for (String columna : List.of("deportista_id", "estado", "es_principal", "autorizado_por", "autorizado_en")) {
			assertThat(vinculo).containsPattern("(?m)^\\s+" + columna + "\\s+");
		}
		assertThat(vinculo).contains("CHECK (estado IN ('PENDIENTE', 'ACTIVO', 'RECHAZADO', 'REVOCADO'))");
		assertThat(vinculo).containsPattern("es_principal\\s+boolean NOT NULL");
	}

	/** Texto de CREATE TABLE gestion_patin.&lt;tabla&gt; ( ... ); en V1. */
	private static String bloqueTabla(String sql, String tabla) {
		int inicio = sql.indexOf("CREATE TABLE gestion_patin." + tabla + " (");
		assertThat(inicio).as("tabla %s en V1", tabla).isGreaterThanOrEqualTo(0);
		return sql.substring(inicio, sql.indexOf("\n);", inicio));
	}
}
