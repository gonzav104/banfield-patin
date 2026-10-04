package com.banfieldpatin.backend.db;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prueba contra una PostgreSQL LOCAL descartable levantada por Testcontainers (requiere Docker).
 * Etiqueta "db": excluida de {@code ./mvnw verify}; se ejecuta con {@code ./mvnw verify -Pdb-tests}.
 * Aplica Flyway (V1 + V2) y valida las entidades con ddl-auto=validate. NUNCA contra Supabase.
 * Sin transaccion de prueba: las pruebas confirman datos reales y los limpian al terminar.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("db")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public @interface PruebaDb {
}
