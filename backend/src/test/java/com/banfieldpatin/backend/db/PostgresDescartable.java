package com.banfieldpatin.backend.db;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Una unica PostgreSQL descartable (Testcontainers) compartida por todas las pruebas "db" de la JVM. Se arranca al
 * cargar la clase y Ryuk la elimina al terminar la JVM. Nunca es la base de la aplicacion ni Supabase: antes de
 * usarla se valida que el host sea loopback (por si DOCKER_HOST apuntara a una maquina remota).
 */
final class PostgresDescartable {

	static final String IMAGEN = "postgres:17-alpine";
	private static final PostgreSQLContainer CONTENEDOR = new PostgreSQLContainer(IMAGEN)
			.withDatabaseName("banfield_test").withUsername("banfield_test").withPassword("banfield_test");

	static {
		CONTENEDOR.start();
		DestinoDbPrueba.validarUrl(CONTENEDOR.getJdbcUrl());
	}

	private PostgresDescartable() {
	}

	static String url() {
		return CONTENEDOR.getJdbcUrl();
	}

	static String usuario() {
		return CONTENEDOR.getUsername();
	}

	static String clave() {
		return CONTENEDOR.getPassword();
	}
}
