package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Migra datos sinteticos V9 a V10 en una base privada del contenedor descartable (FE-05/U9): la revision por fila de
 * deportista_temporada no altera los datos deportivos, ni las unicidades, ni las claves foraneas, y cada fila nace en 0.
 */
@Tag("db")
class V10VersionDeportistaTemporadaDbTest {

	@Test
	void migraFilasExistentesSinCambiarDatosNiRelacionesYLasDejaEnLaRevisionCero() {
		String base = PostgresDescartable.crearBase("v10");
		try {
			var ds = new DriverManagerDataSource(PostgresDescartable.urlDeBase(base), PostgresDescartable.usuario(),
					PostgresDescartable.clave());
			var jdbc = JdbcClient.create(ds);
			var datos = new DatosDb(jdbc);
			flyway(ds, "9").migrate();
			UUID escuela = datos.escuela("v10-" + UUID.randomUUID());
			UUID ana = datos.deportista(escuela, "30111222", "Ana", "Sintetica");
			UUID luis = datos.deportista(escuela, "30111333", "Luis", "Sintetico", false);
			UUID t26 = datos.temporada(escuela, 2026, false);
			UUID t27 = datos.temporada(escuela, 2027, true);
			UUID f1 = datos.datosDeportivos(escuela, ana, t26, "Sub 10", "007", "ACTIVO");
			datos.datosDeportivos(escuela, ana, t27, "Sub 12", "0008", "SUSPENDIDO");
			datos.datosDeportivos(escuela, luis, t27, null, null, "INACTIVO");
			String filasAntes = jdbc.sql("SELECT json_agg(dt ORDER BY id)::text FROM gestion_patin.deportista_temporada dt")
					.query(String.class).single();
			String deportistasAntes = jdbc.sql("SELECT json_agg(d ORDER BY id)::text FROM gestion_patin.deportista d")
					.query(String.class).single();
			String temporadasAntes = jdbc.sql("SELECT json_agg(t ORDER BY id)::text FROM gestion_patin.temporada t")
					.query(String.class).single();

			flyway(ds, "10").migrate();

			// Mismos datos deportivos: la unica diferencia es la columna nueva (numero de corredor con ceros incluido).
			assertThat(jdbc.sql("SELECT jsonb_agg(to_jsonb(dt) - 'version' ORDER BY id) = cast(:antes AS jsonb) FROM gestion_patin.deportista_temporada dt")
					.param("antes", filasAntes).query(Boolean.class).single()).as("los datos deportivos no cambian").isTrue();
			assertThat(jdbc.sql("SELECT json_agg(d ORDER BY id)::text FROM gestion_patin.deportista d").query(String.class).single())
					.as("la ficha permanente no se toca").isEqualTo(deportistasAntes);
			assertThat(jdbc.sql("SELECT json_agg(t ORDER BY id)::text FROM gestion_patin.temporada t").query(String.class).single())
					.as("las temporadas no se tocan").isEqualTo(temporadasAntes);
			assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.deportista_temporada WHERE version = 0").query(Long.class).single())
					.isEqualTo(3L);
			assertThat(jdbc.sql("SELECT numero_corredor FROM gestion_patin.deportista_temporada WHERE temporada_id = :t AND deportista_id = :d")
					.param("t", t27).param("d", ana).query(String.class).single()).as("ceros iniciales").isEqualTo("0008");
			assertThat(jdbc.sql("SELECT estado_deportivo FROM gestion_patin.deportista_temporada WHERE id = :i").param("i", f1)
					.query(String.class).single()).isEqualTo("ACTIVO");

			// Una fila nueva nace en 0 y la revision es BIGINT NOT NULL no negativa.
			UUID eva = datos.deportista(escuela, "30111444", "Eva", "Nueva");
			UUID nueva = datos.datosDeportivos(escuela, eva, t27, "Sub 14", "9", "ACTIVO");
			assertThat(jdbc.sql("SELECT version FROM gestion_patin.deportista_temporada WHERE id = :i").param("i", nueva)
					.query(Long.class).single()).isZero();
			assertThat(jdbc.sql("SELECT data_type FROM information_schema.columns WHERE table_schema = 'gestion_patin' AND table_name = 'deportista_temporada' AND column_name = 'version'")
					.query(String.class).single()).isEqualTo("bigint");
			assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.deportista_temporada SET version = -1 WHERE id = :i").param("i", nueva).update())
					.isInstanceOf(DataIntegrityViolationException.class);
			assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.deportista_temporada SET version = NULL WHERE id = :i").param("i", nueva).update())
					.isInstanceOf(DataIntegrityViolationException.class);

			// Las unicidades y las claves foraneas siguen enteras.
			assertThatThrownBy(() -> datos.datosDeportivos(escuela, ana, t27, "Otra", "5", "ACTIVO"))
					.as("un deportista, una fila por temporada").isInstanceOf(DataIntegrityViolationException.class);
			UUID otro = datos.deportista(escuela, "30111555", "Otro", "Distinto");
			assertThatThrownBy(() -> datos.datosDeportivos(escuela, otro, t27, "Otra", "9", "ACTIVO"))
					.as("un numero de corredor por temporada").isInstanceOf(DataIntegrityViolationException.class);
			UUID otraEscuela = datos.escuela("v10-otra-" + UUID.randomUUID());
			assertThatThrownBy(() -> datos.datosDeportivos(otraEscuela, otro, t27, "X", "1", "ACTIVO"))
					.as("la misma escuela para el deportista y la temporada").isInstanceOf(DataIntegrityViolationException.class);
		} finally {
			PostgresDescartable.eliminarBase(base);
		}
	}

	private Flyway flyway(DriverManagerDataSource ds, String target) {
		return Flyway.configure().dataSource(ds).locations("classpath:db/migration").schemas("gestion_patin")
				.defaultSchema("gestion_patin").target(target).cleanDisabled(true).load();
	}
}
