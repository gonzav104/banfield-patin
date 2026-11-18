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
 * Migra datos sinteticos V10 a V11 en una base privada del contenedor descartable (FE-05/U11-A): la revision de los
 * vinculos es una columna nueva, independiente de {@code version}, que no altera datos ni relaciones existentes.
 */
@Tag("db")
class V11VersionVinculosDbTest {

	@Test
	void migraDeportistasExistentesConRevisionDeVinculosCeroSinTocarSuVersionNiSusVinculos() {
		String base = PostgresDescartable.crearBase("v11");
		try {
			var ds = new DriverManagerDataSource(PostgresDescartable.urlDeBase(base), PostgresDescartable.usuario(),
					PostgresDescartable.clave());
			var jdbc = JdbcClient.create(ds);
			var datos = new DatosDb(jdbc);
			flyway(ds, "10").migrate();
			UUID escuela = datos.escuela("v11-" + UUID.randomUUID());
			UUID familia = datos.familia(escuela, "Familia sintetica", true);
			UUID activo = datos.deportista(escuela, "30111222", "Ana", "Sintetica");
			UUID inactivo = datos.deportista(escuela, "30111333", "Luis", "Sintetico", false);
			UUID autoriza = datos.admin(escuela, "v11@example.invalid", true);
			datos.vinculo(escuela, familia, activo, "ACTIVO", true, autoriza);
			datos.vinculo(escuela, familia, inactivo, "REVOCADO", false, autoriza);
			// Un deportista con datos editados (version > 0) antes de la migracion.
			jdbc.sql("UPDATE gestion_patin.deportista SET version = 7 WHERE id = :i").param("i", activo).update();
			String deportistasAntes = jdbc.sql("SELECT json_agg(d ORDER BY id)::text FROM gestion_patin.deportista d")
					.query(String.class).single();
			String vinculosAntes = jdbc.sql("SELECT json_agg(v ORDER BY id)::text FROM gestion_patin.familia_deportista v")
					.query(String.class).single();

			flyway(ds, "11").migrate();

			assertThat(jdbc.sql("SELECT jsonb_agg(to_jsonb(d) - 'version_vinculos' ORDER BY id) = cast(:antes AS jsonb) "
					+ "FROM gestion_patin.deportista d").param("antes", deportistasAntes).query(Boolean.class).single())
					.as("los datos y la version de los deportistas no cambian").isTrue();
			assertThat(jdbc.sql("SELECT json_agg(v ORDER BY id)::text FROM gestion_patin.familia_deportista v")
					.query(String.class).single()).as("los vinculos no se tocan").isEqualTo(vinculosAntes);
			assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.deportista WHERE version_vinculos = 0")
					.query(Long.class).single()).isEqualTo(2L);
			assertThat(jdbc.sql("SELECT version FROM gestion_patin.deportista WHERE id = :i").param("i", activo)
					.query(Long.class).single()).as("la version de datos permanentes es independiente").isEqualTo(7L);

			UUID nuevo = datos.deportista(escuela, "30111555", "Eva", "Nueva");
			assertThat(jdbc.sql("SELECT version_vinculos FROM gestion_patin.deportista WHERE id = :i").param("i", nuevo)
					.query(Long.class).single()).isZero();
			assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.deportista SET version_vinculos = -1 WHERE id = :i")
					.param("i", nuevo).update()).isInstanceOf(DataIntegrityViolationException.class)
					.hasMessageContaining("ck_deportista_version_vinculos_no_negativa");
			assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.deportista SET version_vinculos = NULL WHERE id = :i")
					.param("i", nuevo).update()).isInstanceOf(DataIntegrityViolationException.class);
			// Los CHECK anteriores siguen vigentes.
			assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.deportista SET version = -1 WHERE id = :i")
					.param("i", nuevo).update()).isInstanceOf(DataIntegrityViolationException.class);
		} finally {
			PostgresDescartable.eliminarBase(base);
		}
	}

	private Flyway flyway(DriverManagerDataSource ds, String target) {
		return Flyway.configure().dataSource(ds).locations("classpath:db/migration").schemas("gestion_patin")
				.defaultSchema("gestion_patin").target(target).cleanDisabled(true).load();
	}
}
