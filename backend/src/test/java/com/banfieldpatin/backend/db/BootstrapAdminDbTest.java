package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.usuarios.bootstrap.BootstrapAdminRunner;

import tools.jackson.databind.json.JsonMapper;

/**
 * Bootstrap del primer ADMIN contra PostgreSQL real, con el runner, el repositorio, la auditoria (JdbcClient) y las
 * migraciones Flyway reales. Reproduce el error de orden de SQL que los mocks no ven: el INSERT de la auditoria no
 * puede ejecutarse antes que el del usuario porque fk_auditoria_usuario_misma_escuela exige que el usuario exista.
 */
@PruebaDb
@Import(BootstrapAdminDbTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BootstrapAdminDbTest extends BaseDbTest {

	private static final String SLUG = "bootstrap-" + UUID.randomUUID();
	private static final String EMAIL = "Director@Bootstrap.example";
	private static final String PASSWORD = "clave-bootstrap-segura-123";

	@TestConfiguration
	@Import({ BootstrapAdminRunner.class, AuditoriaService.class })
	static class Config {

		/** La escuela configurada debe existir antes de que corra el bootstrap (que arranca con el contexto, como en la app). */
		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE)
		ApplicationRunner crearEscuelaConfigurada(JdbcClient jdbc) {
			return args -> new DatosDb(jdbc).escuelaPorSlugOCrear(SLUG);
		}

		@Bean
		PasswordEncoder passwordEncoder() {
			return PasswordEncoderFactories.createDelegatingPasswordEncoder();
		}

		@Bean
		JsonMapper jsonMapper() {
			return JsonMapper.builder().build();
		}
	}

	@DynamicPropertySource
	static void propiedadesDelBootstrap(DynamicPropertyRegistry registro) {
		registro.add("banfield.escuela.slug", () -> SLUG);
		registro.add("banfield.bootstrap-admin.habilitado", () -> "true");
		registro.add("banfield.bootstrap-admin.email", () -> EMAIL);
		registro.add("banfield.bootstrap-admin.nombre", () -> "Ana");
		registro.add("banfield.bootstrap-admin.apellido", () -> "Directora");
		registro.add("banfield.bootstrap-admin.password", () -> PASSWORD);
	}

	@Autowired
	JdbcClient jdbc;
	@Autowired
	BootstrapAdminRunner runner;

	private DatosDb datos;
	private UUID escuelaId;

	@BeforeAll
	void crearEscuela() {
		datos = new DatosDb(jdbc);
		escuelaId = datos.escuelaPorSlugOCrear(SLUG);
	}

	@AfterAll
	void borrarEscuela() {
		datos.limpiarEscuela(escuelaId);
	}

	/** El bootstrap ya corrio al arrancar el contexto: se verifica el estado real que dejo en la base. */
	@Test
	void elBootstrapPersisteElAdminYAudita() {
		var usuario = jdbc.sql("""
				SELECT id, rol, familia_id, email, activo, password_hash FROM gestion_patin.usuario WHERE escuela_id = :e
				""").param("e", escuelaId).query().singleRow();
		assertThat(usuario.get("rol")).isEqualTo("ADMIN");
		assertThat(usuario.get("familia_id")).isNull();
		assertThat(usuario.get("email")).isEqualTo("director@bootstrap.example");
		assertThat(usuario.get("activo")).isEqualTo(true);
		assertThat((String) usuario.get("password_hash")).startsWith("{bcrypt}").doesNotContain(PASSWORD);

		var auditoria = jdbc.sql("""
				SELECT usuario_id, escuela_id, recurso_tipo, recurso_id, detalle::text AS detalle
				FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'ADMIN_BOOTSTRAP'
				""").param("e", escuelaId).query().singleRow();
		assertThat(auditoria.get("usuario_id")).isEqualTo(usuario.get("id"));
		assertThat(auditoria.get("recurso_tipo")).isEqualTo("usuario");
		assertThat(auditoria.get("recurso_id")).isEqualTo(usuario.get("id"));
		String detalle = (String) auditoria.get("detalle");
		assertThat(detalle).doesNotContain(PASSWORD).doesNotContain("director@bootstrap.example")
				.doesNotContain("Director@Bootstrap.example").contains("d***@b***.example");
	}

	@Test
	void unSegundoArranqueEsIdempotente() {
		runner.run(new DefaultApplicationArguments());
		runner.run(new DefaultApplicationArguments());

		assertThat(datos.contarUsuarios(escuelaId, "ADMIN")).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'ADMIN_BOOTSTRAP'")
				.param("e", escuelaId).query(Long.class).single()).isEqualTo(1);
	}
}
