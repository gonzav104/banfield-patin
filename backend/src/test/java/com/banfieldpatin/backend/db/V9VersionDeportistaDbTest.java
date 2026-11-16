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

/** Migra datos sinteticos V8 a V9 en una base privada del contenedor descartable (FE-05/U6). */
@Tag("db")
class V9VersionDeportistaDbTest {
    @Test void migraDeportistasExistentesSinCambiarDatosNiRelacionesYConservaElDefault() {
        String base = PostgresDescartable.crearBase("v9");
        try {
            var ds = new DriverManagerDataSource(PostgresDescartable.urlDeBase(base),
                    PostgresDescartable.usuario(), PostgresDescartable.clave());
            var jdbc = JdbcClient.create(ds);
            var datos = new DatosDb(jdbc);
            flyway(ds, "8").migrate();
            UUID escuela = datos.escuela("v9-" + UUID.randomUUID());
            UUID familia = datos.familia(escuela, "Familia sintetica", true);
            UUID activo = datos.deportista(escuela, "30111222", "Ana", "Sintetica");
            UUID inactivo = datos.deportista(escuela, "30111333", "Luis", "Sintetico", false);
            UUID conCuil = datos.deportistaConCuil(escuela, "30111444", "20301114440", "Eva", "Sintetica");
            UUID autoriza = datos.admin(escuela, "v9@example.invalid", true);
            datos.vinculo(escuela, familia, activo, "ACTIVO", true, autoriza);
            jdbc.sql("UPDATE gestion_patin.deportista SET localidad='Banfield', telefono_contacto='11-5555-0000', email_federativo='ana@example.invalid' WHERE id=:i")
                    .param("i", activo).update();
            String deportistasAntes = jdbc.sql("SELECT json_agg(d ORDER BY id)::text FROM gestion_patin.deportista d").query(String.class).single();
            String vinculosAntes = jdbc.sql("SELECT json_agg(v ORDER BY id)::text FROM gestion_patin.familia_deportista v").query(String.class).single();
            String familiasAntes = jdbc.sql("SELECT json_agg(f ORDER BY id)::text FROM gestion_patin.familia f").query(String.class).single();

            flyway(ds, "9").migrate();

            // Mismos datos y relaciones: la unica diferencia es la columna nueva.
            assertThat(jdbc.sql("SELECT jsonb_agg(to_jsonb(d) - 'version' ORDER BY id) = cast(:antes AS jsonb) FROM gestion_patin.deportista d")
                    .param("antes", deportistasAntes).query(Boolean.class).single())
                    .as("los datos de los deportistas no cambian").isTrue();
            assertThat(jdbc.sql("SELECT json_agg(v ORDER BY id)::text FROM gestion_patin.familia_deportista v").query(String.class).single())
                    .as("los vinculos no se tocan").isEqualTo(vinculosAntes);
            assertThat(jdbc.sql("SELECT json_agg(f ORDER BY id)::text FROM gestion_patin.familia f").query(String.class).single())
                    .as("la tabla familia no se toca").isEqualTo(familiasAntes);
            assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.deportista WHERE version = 0").query(Long.class).single()).isEqualTo(3L);
            assertThat(jdbc.sql("SELECT activo FROM gestion_patin.deportista WHERE id=:i").param("i", inactivo).query(Boolean.class).single())
                    .as("el estado inactivo se conserva").isFalse();
            assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.deportista WHERE cuil IS NOT NULL").query(Long.class).single()).isEqualTo(1L);

            UUID nuevo = datos.deportista(escuela, "30111555", "Eva", "Nueva");
            assertThat(jdbc.sql("SELECT version FROM gestion_patin.deportista WHERE id=:i").param("i", nuevo).query(Long.class).single()).isZero();
            assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.deportista SET version=-1 WHERE id=:i").param("i", conCuil).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.deportista SET version=NULL WHERE id=:i").param("i", conCuil).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
            // La unicidad de DNI sigue entera (incluso entre inactivos).
            assertThatThrownBy(() -> datos.deportista(escuela, "30111333", "Otro", "Duplicado"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally { PostgresDescartable.eliminarBase(base); }
    }

    private Flyway flyway(DriverManagerDataSource ds, String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .schemas("gestion_patin").defaultSchema("gestion_patin").target(target).cleanDisabled(true).load();
    }
}
