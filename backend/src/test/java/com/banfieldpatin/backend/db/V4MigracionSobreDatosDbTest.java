package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V4 sobre datos ya existentes (el caso real de Supabase, que ya tiene V1-V3 aplicadas). Cada prueba usa su propia
 * base descartable DENTRO del contenedor Testcontainers (CREATE DATABASE) y Flyway por API: no toca la base
 * compartida de los contextos Spring ni usa nunca Supabase.
 *
 * <ul>
 * <li>Filas validas previas: V4 se aplica y las filas quedan intactas.</li>
 * <li>Filas que violan la regla (ACTIVO sin autorizacion, dos ACTIVO principales): la migracion FALLA, que es lo
 * que la compuerta G1 espera detectar con los prechecks de solo lectura; PostgreSQL revierte el DDL, de modo que
 * V4 no queda registrada ni queda ningun objeto a medias.</li>
 * </ul>
 */
@Tag("db")
class V4MigracionSobreDatosDbTest {

	private String base;
	private DriverManagerDataSource dataSource;
	private JdbcClient jdbc;
	private DatosDb datos;

	@BeforeEach
	void crearBaseConV1AV3() {
		base = PostgresDescartable.crearBase("v4");
		dataSource = new DriverManagerDataSource(PostgresDescartable.urlDeBase(base), PostgresDescartable.usuario(),
				PostgresDescartable.clave());
		jdbc = JdbcClient.create(dataSource);
		datos = new DatosDb(jdbc);
		flyway("3").migrate();
	}

	@AfterEach
	void eliminarBase() {
		PostgresDescartable.eliminarBase(base);
	}

	private Flyway flyway(String objetivo) {
		return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
				.schemas("gestion_patin").defaultSchema("gestion_patin").target(objetivo)
				.cleanDisabled(true).load();
	}

	private List<String> versionesAplicadas() {
		return jdbc.sql("""
				SELECT version FROM gestion_patin.flyway_schema_history
				WHERE success AND version IS NOT NULL ORDER BY installed_rank
				""")
				.query(String.class).list();
	}

	private long objetosDeV4() {
		return jdbc.sql("""
				SELECT (SELECT count(*) FROM pg_indexes
				         WHERE schemaname = 'gestion_patin' AND indexname IN ('ix_tutor_familia', 'uq_fd_principal_activo'))
				     + (SELECT count(*) FROM pg_constraint WHERE conname = 'ck_fd_activo_autorizado')
				""").query(Long.class).single();
	}

	private record Escenario(UUID escuela, UUID admin, UUID familia1, UUID familia2, UUID deportista) {
	}

	/** Datos validos para V3: escuela, admin, dos familias, un tutor, un deportista y vinculos con autorizacion. */
	private Escenario sembrarValidos() {
		UUID escuela = datos.escuela("v4-datos-" + UUID.randomUUID());
		UUID admin = datos.admin(escuela, "admin@datos.example", true);
		UUID familia1 = datos.familia(escuela, "Familia 1", true);
		UUID familia2 = datos.familia(escuela, "Familia 2", true);
		datos.tutor(escuela, familia1, "Tito", "Tutor");
		UUID deportista = datos.deportista(escuela, "30111222", "Ana", "Perez");
		datos.vinculo(escuela, familia1, deportista, "ACTIVO", true, admin);
		datos.vinculo(escuela, familia2, deportista, "ACTIVO", false, admin);
		UUID otro = datos.deportista(escuela, "30999888", "Beto", "Gomez");
		datos.vinculo(escuela, familia1, otro, "PENDIENTE", true, null);
		datos.vinculo(escuela, familia2, otro, "REVOCADO", true, null);
		return new Escenario(escuela, admin, familia1, familia2, deportista);
	}

	private long contar(String tabla) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin." + tabla).query(Long.class).single();
	}

	@Test
	void laBaseDePartidaTieneSoloV1AV3SinObjetosDeV4() {
		assertThat(versionesAplicadas()).containsExactly("1", "2", "3");
		assertThat(objetosDeV4()).isZero();
	}

	@Test
	void v4SeAplicaSobreFilasValidasPreexistentesYLasConserva() {
		Escenario e = sembrarValidos();

		flyway("4").migrate();

		assertThat(versionesAplicadas()).containsExactly("1", "2", "3", "4");
		assertThat(objetosDeV4()).isEqualTo(3);
		assertThat(contar("familia_deportista")).isEqualTo(4);
		assertThat(contar("tutor")).isEqualTo(1);
		assertThat(contar("deportista")).isEqualTo(2);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM gestion_patin.familia_deportista
				WHERE deportista_id = :d AND estado = 'ACTIVO' AND es_principal
				""").param("d", e.deportista()).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void v4SeAplicaSobreUnaBaseSinFilasDeFamiliaNiVinculos() {
		flyway("4").migrate();

		assertThat(versionesAplicadas()).containsExactly("1", "2", "3", "4");
		assertThat(objetosDeV4()).isEqualTo(3);
	}

	@Test
	void unVinculoActivoSinAutorizacionPreexistenteHaceFallarV4SinDejarNadaAplicado() {
		Escenario e = sembrarValidos();
		UUID otro = datos.deportista(e.escuela(), "30555444", "Carla", "Lopez");
		datos.vinculo(e.escuela(), e.familia1(), otro, "ACTIVO", false, null);

		assertThatThrownBy(() -> flyway("4").migrate()).isInstanceOf(FlywayException.class)
				.hasMessageContaining("ck_fd_activo_autorizado");

		assertThat(versionesAplicadas()).containsExactly("1", "2", "3");
		// El indice ix_tutor_familia se creaba ANTES del CHECK que falla: su ausencia prueba el rollback del DDL.
		assertThat(objetosDeV4()).isZero();
		assertThat(contar("familia_deportista")).isEqualTo(5);
	}

	@Test
	void dosVinculosActivosPrincipalesPreexistentesHacenFallarV4SinDejarNadaAplicado() {
		Escenario e = sembrarValidos();
		UUID familia3 = datos.familia(e.escuela(), "Familia 3", true);
		datos.vinculo(e.escuela(), familia3, e.deportista(), "ACTIVO", true, e.admin());

		assertThatThrownBy(() -> flyway("4").migrate()).isInstanceOf(FlywayException.class)
				.hasMessageContaining("uq_fd_principal_activo");

		assertThat(versionesAplicadas()).containsExactly("1", "2", "3");
		// El CHECK (valido) y ix_tutor_familia se ejecutaban ANTES del indice unico que falla: nada debe quedar.
		assertThat(objetosDeV4()).isZero();
	}

	@Test
	void trasCorregirLosDatosViolatoriosV4SeAplicaEnUnSegundoIntento() {
		Escenario e = sembrarValidos();
		UUID familia3 = datos.familia(e.escuela(), "Familia 3", true);
		datos.vinculo(e.escuela(), familia3, e.deportista(), "ACTIVO", true, e.admin());
		assertThatThrownBy(() -> flyway("4").migrate()).isInstanceOf(FlywayException.class);

		jdbc.sql("UPDATE gestion_patin.familia_deportista SET es_principal = false WHERE familia_id = :f")
				.param("f", familia3).update();
		flyway("4").migrate();

		assertThat(versionesAplicadas()).containsExactly("1", "2", "3", "4");
		assertThat(objetosDeV4()).isEqualTo(3);
	}
}
