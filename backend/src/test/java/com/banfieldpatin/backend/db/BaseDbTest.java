package com.banfieldpatin.backend.db;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Conecta el contexto de prueba con la base local descartable indicada por variables de entorno. */
abstract class BaseDbTest {

	@DynamicPropertySource
	static void propiedadesDeBase(DynamicPropertyRegistry registro) {
		DestinoDbPrueba.Destino destino = DestinoDbPrueba.desdeEntorno(System::getenv);
		registro.add("spring.datasource.url", destino::url);
		registro.add("spring.datasource.username", destino::usuario);
		registro.add("spring.datasource.password", destino::clave);
		registro.add("spring.flyway.enabled", () -> "true");
		registro.add("spring.flyway.clean-disabled", () -> "true");
		registro.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
	}
}
