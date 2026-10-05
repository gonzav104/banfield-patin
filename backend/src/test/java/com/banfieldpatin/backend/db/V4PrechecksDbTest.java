package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Prueba que {@code backend/scripts/precheck-v4-supabase.sql} (solo lectura, para la compuerta humana G1) detecta
 * lo que dice detectar. El script se ejecuta aqui por JDBC sentencia por sentencia contra bases descartables
 * dentro del contenedor Testcontainers; nunca contra Supabase. El mismo archivo lo corre psql con {@code -f}.
 */
@Tag("db")
class V4PrechecksDbTest {

	private static final Path SCRIPT = Path.of("scripts/precheck-v4-supabase.sql");

	private String base;
	private DriverManagerDataSource dataSource;
	private JdbcClient jdbc;
	private DatosDb datos;

	@BeforeEach
	void crearBase() {
		base = PostgresDescartable.crearBase("pre");
		dataSource = new DriverManagerDataSource(PostgresDescartable.urlDeBase(base), PostgresDescartable.usuario(),
				PostgresDescartable.clave());
		jdbc = JdbcClient.create(dataSource);
		datos = new DatosDb(jdbc);
	}

	@AfterEach
	void eliminarBase() {
		PostgresDescartable.eliminarBase(base);
	}

	private void migrarHasta(String version) {
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").schemas("gestion_patin")
				.defaultSchema("gestion_patin").target(version).cleanDisabled(true).load().migrate();
	}

	/** Quita comentarios de linea completa y meta-comandos de psql y divide por punto y coma. */
	private static List<String> sentencias() throws IOException {
		String texto = Files.readAllLines(SCRIPT, StandardCharsets.UTF_8).stream().map(String::strip)
				.filter(l -> !l.startsWith("--") && !l.startsWith("\\")).collect(Collectors.joining("\n"));
		List<String> lista = new ArrayList<>();
		for (String s : texto.split(";")) {
			if (!s.isBlank()) {
				lista.add(s.strip());
			}
		}
		return lista;
	}

	/** Ejecuta el script completo en UNA conexion (como psql) y agrupa las filas por detector. */
	private Map<String, List<Map<String, Object>>> ejecutarScript() throws Exception {
		Map<String, List<Map<String, Object>>> porDetector = new LinkedHashMap<>();
		try (Connection c = DriverManager.getConnection(PostgresDescartable.urlDeBase(base),
				PostgresDescartable.usuario(), PostgresDescartable.clave()); Statement s = c.createStatement()) {
			for (String sentencia : sentencias()) {
				if (!s.execute(sentencia)) {
					continue;
				}
				try (ResultSet rs = s.getResultSet()) {
					var meta = rs.getMetaData();
					assertThat(meta.getColumnLabel(1)).as("la primera columna es el detector").isEqualTo("detector");
					while (rs.next()) {
						Map<String, Object> fila = new LinkedHashMap<>();
						for (int i = 1; i <= meta.getColumnCount(); i++) {
							fila.put(meta.getColumnLabel(i), rs.getObject(i));
						}
						porDetector.computeIfAbsent((String) fila.get("detector"), k -> new ArrayList<>()).add(fila);
					}
				}
			}
			// La sesion quedo en solo lectura: ninguna escritura es posible tras correr el script.
			assertThatThrownBy(() -> s.execute("CREATE TABLE gestion_patin.no_debe_existir (x int)"))
					.isInstanceOf(SQLException.class).hasMessageContaining("read-only");
		}
		return porDetector;
	}

	private static Map<String, List<Map<String, Object>>> soloDetectores(Map<String, List<Map<String, Object>>> todo) {
		Map<String, List<Map<String, Object>>> r = new LinkedHashMap<>(todo);
		r.keySet().removeIf(k -> k.startsWith("INFO_"));
		return r;
	}

	private void sembrarValidos() {
		UUID escuela = datos.escuela("pre-ok-" + UUID.randomUUID());
		UUID admin = datos.admin(escuela, "admin@pre.example", true);
		UUID f1 = datos.familia(escuela, "Familia 1", true);
		UUID f2 = datos.familia(escuela, "Familia 2", true);
		datos.tutor(escuela, f1, "Tito", "Tutor");
		UUID d = datos.deportista(escuela, "30111222", "Ana", "Perez");
		datos.vinculo(escuela, f1, d, "ACTIVO", true, admin);
		datos.vinculo(escuela, f2, d, "ACTIVO", false, admin);
		datos.vinculo(escuela, f2, datos.deportista(escuela, "30999888", "Beto", "Gomez"), "PENDIENTE", true, null);
	}

	@Test
	void elScriptEmpiezaEnModoSoloLecturaYContieneSoloConsultas() throws Exception {
		List<String> sentencias = sentencias();

		assertThat(sentencias.get(0)).isEqualTo("set default_transaction_read_only = on");
		assertThat(sentencias.subList(1, sentencias.size())).isNotEmpty()
				.allSatisfy(s -> assertThat(s.toLowerCase()).startsWith("select"));
		String sinComentarios = String.join("\n", sentencias).toLowerCase();
		for (String prohibida : List.of("insert ", "update ", "delete ", "drop ", "alter ", "create ", "truncate",
				"grant ", "revoke ")) {
			assertThat(sinComentarios).as("el script no debe contener '%s'", prohibida).doesNotContain(prohibida);
		}
	}

	@Test
	void conUnaBaseLimpiaV1AV3TodosLosDetectoresVienenVacios() throws Exception {
		migrarHasta("3");
		sembrarValidos();

		var resultado = ejecutarScript();

		assertThat(soloDetectores(resultado)).as("detectores con hallazgos").isEmpty();
		assertThat(resultado.get("INFO_HISTORIAL")).extracting(f -> f.get("version")).containsExactly("1", "2", "3");
		assertThat(resultado.get("INFO_CONTEOS")).singleElement().satisfies(f -> {
			assertThat(f.get("familias")).isEqualTo(2L);
			assertThat(f.get("tutores")).isEqualTo(1L);
			assertThat(f.get("deportistas")).isEqualTo(2L);
			assertThat(f.get("vinculos")).isEqualTo(3L);
			assertThat(f.get("usuarios")).isEqualTo(1L);
		});
	}

	@Test
	void conUnaBaseVaciaV1AV3TodosLosDetectoresVienenVacios() throws Exception {
		migrarHasta("3");

		var resultado = ejecutarScript();

		assertThat(soloDetectores(resultado)).isEmpty();
		assertThat(resultado.get("INFO_CONTEOS")).singleElement().satisfies(f -> assertThat(f.get("vinculos")).isEqualTo(0L));
	}

	@Test
	void detectaVinculoActivoSinAutorizacionDosPrincipalesYNombresEnConflicto() throws Exception {
		migrarHasta("3");
		UUID escuela = datos.escuela("pre-mal-" + UUID.randomUUID());
		UUID admin = datos.admin(escuela, "admin@pre.example", true);
		UUID f1 = datos.familia(escuela, "Familia 1", true);
		UUID f2 = datos.familia(escuela, "Familia 2", true);
		UUID sinAutorizar = datos.deportista(escuela, "30111222", "Ana", "Perez");
		UUID conDosPrincipales = datos.deportista(escuela, "30999888", "Beto", "Gomez");
		UUID vinculoMalo = datos.vinculo(escuela, f1, sinAutorizar, "ACTIVO", false, null);
		datos.vinculo(escuela, f1, conDosPrincipales, "ACTIVO", true, admin);
		datos.vinculo(escuela, f2, conDosPrincipales, "ACTIVO", true, admin);
		// Objetos preexistentes con los nombres que V4 quiere crear.
		jdbc.sql("CREATE INDEX ix_tutor_familia ON gestion_patin.usuario (id)").update();
		jdbc.sql("CREATE TABLE gestion_patin.uq_fd_principal_activo (x int)").update();
		jdbc.sql("ALTER TABLE gestion_patin.tutor ADD CONSTRAINT ck_fd_activo_autorizado CHECK (true)").update();

		var resultado = soloDetectores(ejecutarScript());

		assertThat(resultado).containsOnlyKeys("B_ACTIVO_SIN_AUTORIZACION", "C_DEPORTISTA_CON_VARIOS_PRINCIPALES",
				"D1_RELACION_CON_NOMBRE_EN_CONFLICTO", "D2_RESTRICCION_CON_NOMBRE_EN_CONFLICTO");
		assertThat(resultado.get("B_ACTIVO_SIN_AUTORIZACION")).singleElement().satisfies(f -> {
			assertThat(f.get("vinculo_id")).isEqualTo(vinculoMalo);
			assertThat(f.get("falta_autorizado_por")).isEqualTo(true);
			assertThat(f.get("total")).isEqualTo(1L);
		});
		assertThat(resultado.get("C_DEPORTISTA_CON_VARIOS_PRINCIPALES")).singleElement().satisfies(f -> {
			assertThat(f.get("deportista_id")).isEqualTo(conDosPrincipales);
			assertThat(f.get("principales_activos")).isEqualTo(2L);
		});
		assertThat(resultado.get("D1_RELACION_CON_NOMBRE_EN_CONFLICTO")).extracting(f -> f.get("nombre"))
				.containsExactly("ix_tutor_familia", "uq_fd_principal_activo");
		assertThat(resultado.get("D2_RESTRICCION_CON_NOMBRE_EN_CONFLICTO")).singleElement()
				.satisfies(f -> assertThat(f.get("tabla")).isEqualTo("tutor"));
	}

	@Test
	void detectaUnVinculoActivoSinAutorizadoEnAunqueTengaAutorizadoPor() throws Exception {
		migrarHasta("3");
		UUID escuela = datos.escuela("pre-en-" + UUID.randomUUID());
		UUID admin = datos.admin(escuela, "admin@pre.example", true);
		UUID f1 = datos.familia(escuela, "Familia 1", true);
		UUID d = datos.deportista(escuela, "30111222", "Ana", "Perez");
		UUID vinculo = datos.vinculo(escuela, f1, d, "ACTIVO", false, admin);
		jdbc.sql("UPDATE gestion_patin.familia_deportista SET autorizado_en = NULL WHERE id = :id").param("id", vinculo)
				.update();

		var resultado = soloDetectores(ejecutarScript());

		assertThat(resultado).containsOnlyKeys("B_ACTIVO_SIN_AUTORIZACION");
		assertThat(resultado.get("B_ACTIVO_SIN_AUTORIZACION")).singleElement().satisfies(f -> {
			assertThat(f.get("falta_autorizado_por")).isEqualTo(false);
			assertThat(f.get("falta_autorizado_en")).isEqualTo(true);
		});
	}

	@Test
	void detectaQueV4YaEstaAplicadaEnElHistorial() throws Exception {
		migrarHasta("4");

		var resultado = soloDetectores(ejecutarScript());

		assertThat(resultado).containsKey("A_HISTORIAL_INESPERADO");
		assertThat(resultado.get("A_HISTORIAL_INESPERADO")).singleElement()
				.satisfies(f -> assertThat((String) f.get("versiones_registradas")).contains("4:ok"));
		// Con V4 aplicada sus tres objetos ya existen: tambien se reportan como conflicto de nombres.
		assertThat(resultado).containsKeys("D1_RELACION_CON_NOMBRE_EN_CONFLICTO",
				"D2_RESTRICCION_CON_NOMBRE_EN_CONFLICTO");
	}

	@Test
	void detectaColumnasYFuncionDeTriggerFaltantes() throws Exception {
		migrarHasta("3");
		jdbc.sql("ALTER TABLE gestion_patin.tutor RENAME COLUMN familia_id TO familia_id_x").update();
		jdbc.sql("DROP FUNCTION gestion_patin.actualizar_timestamp_modificacion() CASCADE").update();

		var resultado = soloDetectores(ejecutarScript());

		assertThat(resultado).containsOnlyKeys("D3_FALTA_COLUMNA", "D4_FALTA_FUNCION_TRIGGER");
		assertThat(resultado.get("D3_FALTA_COLUMNA")).singleElement().satisfies(f -> {
			assertThat(f.get("tabla")).isEqualTo("tutor");
			assertThat(f.get("columna")).isEqualTo("familia_id");
		});
	}
}
