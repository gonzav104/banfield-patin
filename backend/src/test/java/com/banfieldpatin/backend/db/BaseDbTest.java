package com.banfieldpatin.backend.db;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Conecta el contexto de prueba con la PostgreSQL descartable levantada por Testcontainers. */
abstract class BaseDbTest {

	@DynamicPropertySource
	static void propiedadesDeBase(DynamicPropertyRegistry registro) {
		registro.add("spring.datasource.url", PostgresDescartable::url);
		registro.add("spring.datasource.username", PostgresDescartable::usuario);
		registro.add("spring.datasource.password", PostgresDescartable::clave);
		registro.add("spring.flyway.enabled", () -> "true");
		registro.add("spring.flyway.clean-disabled", () -> "true");
		registro.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
	}
}
