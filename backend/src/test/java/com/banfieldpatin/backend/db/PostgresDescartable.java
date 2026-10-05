package com.banfieldpatin.backend.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

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

	/**
	 * Crea una base vacia, nueva y descartable DENTRO del mismo contenedor (su nombre lleva "test" para pasar la
	 * salvaguarda de {@link DestinoDbPrueba}) y devuelve su nombre. No toca la base compartida de los contextos
	 * Spring; hay que eliminarla con {@link #eliminarBase}.
	 */
	static String crearBase(String prefijo) {
		String nombre = "banfield_test_" + prefijo + "_" + UUID.randomUUID().toString().replace("-", "");
		ejecutarEnBaseAdmin("CREATE DATABASE " + nombre);
		return nombre;
	}

	static void eliminarBase(String nombre) {
		ejecutarEnBaseAdmin("DROP DATABASE IF EXISTS " + nombre + " WITH (FORCE)");
	}

	/** URL JDBC de una base creada con {@link #crearBase}; mismo host, usuario y clave que la base compartida. */
	static String urlDeBase(String nombre) {
		String url = CONTENEDOR.getJdbcUrl().replaceFirst("/" + CONTENEDOR.getDatabaseName(), "/" + nombre);
		DestinoDbPrueba.validarUrl(url);
		return url;
	}

	/** El nombre solo lo generan {@link #crearBase} (hexadecimal y prefijo fijo de las pruebas): nunca entrada externa. */
	private static void ejecutarEnBaseAdmin(String sql) {
		try (Connection c = DriverManager.getConnection(url(), usuario(), clave()); Statement s = c.createStatement()) {
			s.execute(sql);
		} catch (SQLException e) {
			throw new IllegalStateException("No se pudo ejecutar la sentencia administrativa de prueba", e);
		}
	}

	static String usuario() {
		return CONTENEDOR.getUsername();
	}

	static String clave() {
		return CONTENEDOR.getPassword();
	}
}
