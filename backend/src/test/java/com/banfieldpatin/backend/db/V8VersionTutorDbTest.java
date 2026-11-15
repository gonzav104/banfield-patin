package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Migra datos sinteticos V7 a V8 en una base privada del contenedor descartable (FE-05/U4). */
@Tag("db")
class V8VersionTutorDbTest {
    @Test void migraTutoresExistentesSinCambiarDatosNiRelacionesYConservaElDefault() {
        String base = PostgresDescartable.crearBase("v8");
        try {
            var ds = new DriverManagerDataSource(PostgresDescartable.urlDeBase(base),
                    PostgresDescartable.usuario(), PostgresDescartable.clave());
            var jdbc = JdbcClient.create(ds);
            var datos = new DatosDb(jdbc);
            flyway(ds, "7").migrate();
            UUID escuela = datos.escuela("v8-" + UUID.randomUUID());
            UUID familia = datos.familia(escuela, "Familia sintetica", true);
            UUID inactiva = datos.familia(escuela, "Familia inactiva", false);
            UUID conDatos = datos.tutor(escuela, familia, "Ana", "Sintetica");
            UUID enInactiva = datos.tutor(escuela, inactiva, "Luis", "Sintetico");
            datos.desactivarTutor(enInactiva);
            jdbc.sql("UPDATE gestion_patin.tutor SET dni='30111222', telefono='11-5555-0000', email='ana@example.invalid', parentesco='Madre' WHERE id=:i")
                    .param("i", conDatos).update();
            String tutoresAntes = jdbc.sql("SELECT json_agg(t ORDER BY id)::text FROM gestion_patin.tutor t").query(String.class).single();
            String familiasAntes = jdbc.sql("SELECT json_agg(f ORDER BY id)::text FROM gestion_patin.familia f").query(String.class).single();

            flyway(ds, "8").migrate();

            // Mismos datos y relaciones: la unica diferencia es la columna nueva.
            assertThat(jdbc.sql("SELECT jsonb_agg(to_jsonb(t) - 'version' ORDER BY id) = cast(:antes AS jsonb) FROM gestion_patin.tutor t")
                    .param("antes", tutoresAntes).query(Boolean.class).single())
                    .as("los datos de los tutores no cambian").isTrue();
            assertThat(jdbc.sql("SELECT json_agg(f ORDER BY id)::text FROM gestion_patin.familia f").query(String.class).single())
                    .isEqualTo(familiasAntes).as("la tabla familia no se toca");
            assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.tutor WHERE version = 0").query(Long.class).single()).isEqualTo(2L);
            assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.tutor WHERE familia_id=:f").param("f", inactiva).query(Long.class).single()).isEqualTo(1L);

            UUID nuevo = datos.tutor(escuela, familia, "Eva", "Sintetica");
            assertThat(jdbc.sql("SELECT version FROM gestion_patin.tutor WHERE id=:i").param("i", nuevo).query(Long.class).single()).isZero();
            assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.tutor SET version=-1 WHERE id=:i").param("i", conDatos).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.tutor SET version=NULL WHERE id=:i").param("i", conDatos).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally { PostgresDescartable.eliminarBase(base); }
    }

    private Flyway flyway(DriverManagerDataSource ds, String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .schemas("gestion_patin").defaultSchema("gestion_patin").target(target).cleanDisabled(true).load();
    }
}
