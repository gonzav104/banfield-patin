package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;

/** Migra datos sinteticos V6 a V7 en una base privada del contenedor descartable. */
@Tag("db")
class V7VersionFamiliaDbTest {
    @Test void migraFamiliasExistentesSinCambiarDatosNiRelacionesYConservaElDefault() {
        String base = PostgresDescartable.crearBase("v7");
        try {
            var ds = new DriverManagerDataSource(PostgresDescartable.urlDeBase(base),
                    PostgresDescartable.usuario(), PostgresDescartable.clave());
            var jdbc = JdbcClient.create(ds);
            var datos = new DatosDb(jdbc);
            flyway(ds, "6").migrate();
            UUID escuela = datos.escuela("v7-" + UUID.randomUUID());
            UUID familia = datos.familia(escuela, "Familia sintetica", false);
            datos.tutor(escuela, familia, "Tutor", "Sintetico");
            String antes = jdbc.sql("SELECT row_to_json(f)::text FROM gestion_patin.familia f WHERE id=:i")
                    .param("i", familia).query(String.class).single();
            String tutores = jdbc.sql("SELECT json_agg(t ORDER BY id)::text FROM gestion_patin.tutor t").query(String.class).single();
            flyway(ds, "7").migrate();
            assertThat(jdbc.sql("SELECT (to_jsonb(f)-'version')::text FROM gestion_patin.familia f WHERE id=:i")
                    .param("i", familia).query(String.class).single())
                    .isEqualTo(jdbc.sql("SELECT cast(cast(:antes AS jsonb) AS text)").param("antes", antes).query(String.class).single());
            assertThat(jdbc.sql("SELECT json_agg(t ORDER BY id)::text FROM gestion_patin.tutor t").query(String.class).single()).isEqualTo(tutores);
            assertThat(jdbc.sql("SELECT version FROM gestion_patin.familia WHERE id=:i").param("i", familia).query(Long.class).single()).isZero();
            UUID nueva = datos.familia(escuela, "Nueva sintetica", true);
            assertThat(jdbc.sql("SELECT version FROM gestion_patin.familia WHERE id=:i").param("i", nueva).query(Long.class).single()).isZero();
            assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.familia SET version=-1 WHERE id=:i").param("i", familia).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.familia SET version=NULL WHERE id=:i").param("i", familia).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally { PostgresDescartable.eliminarBase(base); }
    }
    private Flyway flyway(DriverManagerDataSource ds, String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .schemas("gestion_patin").defaultSchema("gestion_patin").target(target).cleanDisabled(true).load();
    }
}
