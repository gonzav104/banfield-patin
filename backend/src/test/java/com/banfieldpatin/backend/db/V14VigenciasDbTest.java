package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.banfieldpatin.backend.familias.responsables.TutorDeportista;

@Tag("db")
class V14VigenciasDbTest {

	private String base;
	private DriverManagerDataSource ds;
	private JdbcClient jdbc;
	private UUID escuela, familia, otraFamilia, deportista, tutor, otroTutor, admin, vinculo;

	@BeforeEach
	void prepararV13ConDatos() {
		base = PostgresDescartable.crearBase("v14");
		ds = new DriverManagerDataSource(PostgresDescartable.urlDeBase(base), PostgresDescartable.usuario(),
				PostgresDescartable.clave());
		jdbc = JdbcClient.create(ds);
		assertThat(flyway("13").migrate().migrationsExecuted).isEqualTo(13);
		var datos = new DatosDb(jdbc);
		escuela = datos.escuela("v14-a");
		admin = datos.admin(escuela, "admin@a.example.invalid", true);
		familia = datos.familia(escuela, "A", true);
		otraFamilia = datos.familia(escuela, "B", true);
		deportista = datos.deportista(escuela, "V14-1", "Deportista", "Uno");
		tutor = datos.tutor(escuela, familia, "Tutor", "Uno");
		otroTutor = datos.tutor(escuela, otraFamilia, "Tutor", "Dos");
		vinculo = datos.vinculo(escuela, familia, deportista, "ACTIVO", true, admin);
		datos.vinculo(escuela, otraFamilia, deportista, "ACTIVO", false, admin);
		assertThat(flyway("14").migrate().migrationsExecuted).isEqualTo(1);
		flyway("14").validate();
	}

	@AfterEach
	void descartarBase() {
		if (base != null) PostgresDescartable.eliminarBase(base);
	}

	private Flyway flyway(String version) {
		return Flyway.configure().dataSource(ds).locations("classpath:db/migration").schemas("gestion_patin")
				.defaultSchema("gestion_patin").target(version).cleanDisabled(true).load();
	}

	@Test
	void v13ConDatosActualizaYValidaElMapeoSinCrearVigencias() {
		assertThat(contar("tutor_deportista")).isZero();
		assertThat(contar("familia_deportista")).isEqualTo(2);
		assertThat(contar("tutor")).isEqualTo(2);
		var registro = new StandardServiceRegistryBuilder()
				.applySetting("hibernate.connection.datasource", ds)
				.applySetting("hibernate.default_schema", "gestion_patin")
				.applySetting("hibernate.physical_naming_strategy", new PhysicalNamingStrategySnakeCaseImpl())
				.applySetting("hibernate.hbm2ddl.auto", "validate")
				.build();
		try (var sesion = new MetadataSources(registro).addAnnotatedClass(TutorDeportista.class)
				.buildMetadata().buildSessionFactory()) {
			assertThat(sesion.isOpen()).isTrue();
		} finally {
			StandardServiceRegistryBuilder.destroy(registro);
		}
	}

	@Test
	void aislaEscuelaFamiliaYPropietarioAunqueElDeportistaTengaDosFamilias() {
		var datos = new DatosDb(jdbc);
		var externa = datos.escuela("v14-b");
		var familiaExterna = datos.familia(externa, "Externa", true);
		var tutorExterno = datos.tutor(externa, familiaExterna, "Tutor", "Externo");
		falla("23503", () -> insertar(escuela, familia, tutorExterno));
		falla("23503", () -> insertar(escuela, familia, otroTutor));
		falla("23503", () -> insertar(externa, familiaExterna, tutorExterno));

		insertar(escuela, familia, tutor);
		insertar(escuela, otraFamilia, otroTutor);
		falla("23505", () -> insertar(escuela, familia, tutor));
		assertThat(contar("tutor_deportista")).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = { "tutor", "familia", "deportista", "familia_deportista" })
	void soloSePuedeCambiarElEstadoDelPadreDespuesDeCerrarYNoSeReabre(String padre) {
		var id = insertar(escuela, familia, tutor);
		var actualizarBaja = switch (padre) {
			case "tutor", "deportista" -> "activo=false";
			case "familia" -> "activa=false";
			default -> "estado='REVOCADO'";
		};
		var actualizarAlta = switch (padre) {
			case "tutor", "deportista" -> "activo=true";
			case "familia" -> "activa=true";
			default -> "estado='ACTIVO'";
		};
		var padreId = switch (padre) {
			case "tutor" -> tutor;
			case "familia" -> familia;
			case "deportista" -> deportista;
			default -> vinculo;
		};
		falla("23503", () -> actualizar(padre, actualizarBaja, padreId));
		assertThat(actualizar("tutor_deportista",
				"cerrado_en=clock_timestamp(), cerrado_por=creado_por, causa_cierre='REVOCACION_ADMIN', version=1", id))
				.isEqualTo(1);
		assertThat(actualizar(padre, actualizarBaja, padreId)).isEqualTo(1);
		falla("23503", () -> insertar(escuela, familia, tutor));
		assertThat(actualizar(padre, actualizarAlta, padreId)).isEqualTo(1);
		falla("23514", () -> actualizar("tutor_deportista",
				"cerrado_en=NULL, cerrado_por=NULL, causa_cierre=NULL", id));
		falla("23514", () -> jdbc.sql("DELETE FROM gestion_patin.tutor_deportista WHERE id=:id").param("id", id).update());
		assertThat(insertar(escuela, familia, tutor)).isNotEqualTo(id);
		assertThat(contar("tutor_deportista")).isEqualTo(2);
	}

	private UUID insertar(UUID e, UUID f, UUID t) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.tutor_deportista
				    (escuela_id, familia_id, deportista_id, tutor_id, creado_por, solicitud_id, huella_solicitud)
				VALUES (:e, :f, :d, :t, :a, gen_random_uuid(), repeat('a', 64))
				RETURNING id
				""").param("e", e).param("f", f).param("d", deportista).param("t", t).param("a", admin)
				.query(UUID.class).single();
	}

	private int actualizar(String tabla, String expresion, UUID id) {
		return jdbc.sql("UPDATE gestion_patin." + tabla + " SET " + expresion + " WHERE id=:id")
				.param("id", id).update();
	}

	private long contar(String tabla) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin." + tabla).query(Long.class).single();
	}

	private static void falla(String sqlState, Runnable operacion) {
		assertThatThrownBy(operacion::run).rootCause().isInstanceOfSatisfying(SQLException.class,
				e -> assertThat(e.getSQLState()).isEqualTo(sqlState));
	}
}
