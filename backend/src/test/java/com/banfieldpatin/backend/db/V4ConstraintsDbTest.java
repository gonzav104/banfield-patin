package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Restricciones de V4 (vinculos y tutores) contra PostgreSQL 17 real. Que el contexto arranque ya prueba que Flyway
 * aplica V1 + V2 + V3 + V4 en orden sobre el esquema real. REQ-VIN-10 S1-S4, REQ-VIN-08 S1.
 */
@PruebaDb
class V4ConstraintsDbTest extends BaseDbTest {

	@Autowired
	JdbcClient jdbc;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UUID adminA;
	private UUID adminB;
	private UUID familiaA1;
	private UUID familiaA2;
	private UUID familiaA3;
	private UUID familiaB;
	private UUID deportistaA;
	private UUID deportistaB;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("v4-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("v4-b-" + UUID.randomUUID());
		adminA = datos.admin(escuelaA, "admin@a.example", true);
		adminB = datos.admin(escuelaB, "admin@b.example", true);
		familiaA1 = datos.familia(escuelaA, "Familia A1", true);
		familiaA2 = datos.familia(escuelaA, "Familia A2", true);
		familiaA3 = datos.familia(escuelaA, "Familia A3", true);
		familiaB = datos.familia(escuelaB, "Familia B", true);
		deportistaA = datos.deportista(escuelaA, "30111222", "Ana", "Perez");
		deportistaB = datos.deportista(escuelaB, "30111222", "Beto", "Gomez");
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private long principalesActivos(UUID deportistaId) {
		return jdbc.sql("""
				SELECT count(*) FROM gestion_patin.familia_deportista
				WHERE deportista_id = :d AND estado = 'ACTIVO' AND es_principal
				""").param("d", deportistaId).query(Long.class).single();
	}

	// ---------- migracion ----------

	@Test
	void v4SeAplicaSobreV1V2V3() {
		List<String> versiones = jdbc.sql("""
				SELECT version FROM gestion_patin.flyway_schema_history
				WHERE success AND version IS NOT NULL ORDER BY installed_rank
				""")
				.query(String.class).list();

		assertThat(versiones).containsExactly("1", "2", "3", "4");
	}

	@Test
	void existenLosTresObjetosDeV4() {
		var indice = jdbc.sql("""
				SELECT indexdef FROM pg_indexes
				WHERE schemaname = 'gestion_patin' AND tablename = 'tutor' AND indexname = 'ix_tutor_familia'
				""").query(String.class).optional();
		assertThat(indice).isPresent();
		assertThat(indice.get()).contains("(escuela_id, familia_id)");

		var unico = jdbc.sql("""
				SELECT indexdef FROM pg_indexes
				WHERE schemaname = 'gestion_patin' AND tablename = 'familia_deportista'
				  AND indexname = 'uq_fd_principal_activo'
				""").query(String.class).optional();
		assertThat(unico).isPresent();
		assertThat(unico.get()).contains("UNIQUE").contains("(deportista_id)").contains("ACTIVO");

		assertThat(jdbc.sql("""
				SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace
				WHERE n.nspname = 'gestion_patin' AND t.relname = 'familia_deportista'
				  AND c.conname = 'ck_fd_activo_autorizado' AND c.contype = 'c'
				""").query(Long.class).single()).isEqualTo(1);
	}

	// ---------- uq_fd_principal_activo (REQ-VIN-10 S1-S4) ----------

	@Test
	void unSegundoVinculoActivoPrincipalDelMismoDeportistaSeRechaza() {
		datos.vinculo(escuelaA, familiaA1, deportistaA, "ACTIVO", true, adminA);

		assertThatThrownBy(() -> datos.vinculo(escuelaA, familiaA2, deportistaA, "ACTIVO", true, adminA))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_fd_principal_activo");
		assertThat(principalesActivos(deportistaA)).isEqualTo(1);
	}

	@Test
	void promoverAPrincipalActivoConOtroPrincipalVigenteTambienSeRechaza() {
		datos.vinculo(escuelaA, familiaA1, deportistaA, "ACTIVO", true, adminA);
		datos.vinculo(escuelaA, familiaA2, deportistaA, "ACTIVO", false, adminA);

		assertThatThrownBy(() -> jdbc.sql("""
				UPDATE gestion_patin.familia_deportista SET es_principal = true
				WHERE familia_id = :f AND deportista_id = :d
				""").param("f", familiaA2).param("d", deportistaA).update())
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_fd_principal_activo");
	}

	@Test
	void dosVinculosActivosNoPrincipalesSeAceptan() {
		datos.vinculo(escuelaA, familiaA1, deportistaA, "ACTIVO", false, adminA);
		datos.vinculo(escuelaA, familiaA2, deportistaA, "ACTIVO", false, adminA);

		assertThat(principalesActivos(deportistaA)).isZero();
	}

	@Test
	void unPrincipalActivoConviveConOtrosActivosNoPrincipales() {
		datos.vinculo(escuelaA, familiaA1, deportistaA, "ACTIVO", true, adminA);
		datos.vinculo(escuelaA, familiaA2, deportistaA, "ACTIVO", false, adminA);
		datos.vinculo(escuelaA, familiaA3, deportistaA, "ACTIVO", false, adminA);

		assertThat(principalesActivos(deportistaA)).isEqualTo(1);
	}

	@Test
	void unPrincipalRevocadoNoImpideUnNuevoPrincipalActivo() {
		datos.vinculo(escuelaA, familiaA1, deportistaA, "REVOCADO", true, adminA);

		assertThatCode(() -> datos.vinculo(escuelaA, familiaA2, deportistaA, "ACTIVO", true, adminA))
				.doesNotThrowAnyException();
		assertThat(principalesActivos(deportistaA)).isEqualTo(1);
	}

	@Test
	void variosPrincipalesNoActivosSeAceptan() {
		datos.vinculo(escuelaA, familiaA1, deportistaA, "REVOCADO", true, adminA);
		datos.vinculo(escuelaA, familiaA2, deportistaA, "PENDIENTE", true, null);
		datos.vinculo(escuelaA, familiaA3, deportistaA, "RECHAZADO", true, null);

		assertThat(principalesActivos(deportistaA)).isZero();
	}

	@Test
	void cadaDeportistaTieneSuPropioPrincipalActivo() {
		UUID otro = datos.deportista(escuelaA, "30999888", "Carla", "Lopez");
		datos.vinculo(escuelaA, familiaA1, deportistaA, "ACTIVO", true, adminA);

		assertThatCode(() -> datos.vinculo(escuelaA, familiaA1, otro, "ACTIVO", true, adminA))
				.doesNotThrowAnyException();
	}

	// ---------- ck_fd_activo_autorizado (REQ-VIN-10 S5, REQ-VIN-06) ----------

	@Test
	void unVinculoActivoSinAutorizadoPorSeRechaza() {
		assertThatThrownBy(() -> datos.vinculo(escuelaA, familiaA1, deportistaA, "ACTIVO", false, null))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_fd_activo_autorizado");
	}

	@Test
	void unVinculoActivoSinAutorizadoEnSeRechaza() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO gestion_patin.familia_deportista
				    (escuela_id, familia_id, deportista_id, estado, es_principal, autorizado_por, autorizado_en)
				VALUES (:e, :f, :d, 'ACTIVO', false, :ap, NULL)
				""").param("e", escuelaA).param("f", familiaA1).param("d", deportistaA).param("ap", adminA).update())
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_fd_activo_autorizado");
	}

	@Test
	void unVinculoExistenteNoPuedePasarAActivoSinAutorizacion() {
		datos.vinculo(escuelaA, familiaA1, deportistaA, "PENDIENTE", false, null);

		assertThatThrownBy(() -> jdbc.sql("""
				UPDATE gestion_patin.familia_deportista SET estado = 'ACTIVO'
				WHERE familia_id = :f AND deportista_id = :d
				""").param("f", familiaA1).param("d", deportistaA).update())
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_fd_activo_autorizado");
	}

	@Test
	void losEstadosNoActivosSeAceptanSinAutorizacion() {
		datos.vinculo(escuelaA, familiaA1, deportistaA, "PENDIENTE", false, null);
		datos.vinculo(escuelaA, familiaA2, deportistaA, "RECHAZADO", false, null);
		datos.vinculo(escuelaA, familiaA3, deportistaA, "REVOCADO", false, null);

		var estados = jdbc.sql("SELECT estado FROM gestion_patin.familia_deportista WHERE deportista_id = :d ORDER BY estado")
				.param("d", deportistaA).query(String.class).list();
		assertThat(estados).containsExactly("PENDIENTE", "RECHAZADO", "REVOCADO");
	}

	@Test
	void unVinculoActivoConAutorizacionCompletaSeAcepta() {
		datos.vinculo(escuelaA, familiaA1, deportistaA, "ACTIVO", true, adminA);

		var fila = jdbc.sql("SELECT autorizado_por, autorizado_en FROM gestion_patin.familia_deportista WHERE familia_id = :f")
				.param("f", familiaA1).query().singleRow();
		assertThat(fila.get("autorizado_por")).isEqualTo(adminA);
		assertThat(fila.get("autorizado_en")).isNotNull();
	}

	// ---------- FK compuestas por escuela (siguen vigentes, REQ-VIN-08 S1) ----------

	@Test
	void unTutorNoPuedeReferenciarUnaFamiliaDeOtraEscuela() {
		assertThatThrownBy(() -> datos.tutor(escuelaA, familiaB, "Tito", "Tutor"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_tutor_familia_misma_escuela");
	}

	@Test
	void unVinculoNoPuedeReferenciarUnaFamiliaDeOtraEscuela() {
		assertThatThrownBy(() -> datos.vinculo(escuelaA, familiaB, deportistaA, "ACTIVO", false, adminA))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_fd_familia_misma_escuela");
	}

	@Test
	void unVinculoNoPuedeReferenciarUnDeportistaDeOtraEscuela() {
		assertThatThrownBy(() -> datos.vinculo(escuelaA, familiaA1, deportistaB, "ACTIVO", false, adminA))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_fd_deportista_misma_escuela");
	}

	@Test
	void unVinculoNoPuedeSerAutorizadoPorUnUsuarioDeOtraEscuela() {
		assertThatThrownBy(() -> datos.vinculo(escuelaA, familiaA1, deportistaA, "ACTIVO", false, adminB))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_fd_autorizado_por_misma_escuela");
	}

	// ---------- ix_tutor_familia y datos de tutor ----------

	@Test
	void losTutoresDeLaMismaFamiliaSeInsertanYSeListanPorEscuelaYFamilia() {
		datos.tutor(escuelaA, familiaA1, "Tito", "Tutor");
		datos.tutor(escuelaA, familiaA1, "Tati", "Tutor");
		datos.tutor(escuelaA, familiaA2, "Otro", "Tutor");

		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.tutor WHERE escuela_id = :e AND familia_id = :f")
				.param("e", escuelaA).param("f", familiaA1).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void limpiarContenidoBorraVinculosTutoresYDeportistasEnOrdenDeDependencias() {
		datos.tutor(escuelaA, familiaA1, "Tito", "Tutor");
		datos.vinculo(escuelaA, familiaA1, deportistaA, "ACTIVO", true, adminA);

		datos.limpiarContenido(escuelaA);

		for (String tabla : List.of("familia_deportista", "tutor", "deportista", "usuario", "familia")) {
			assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin." + tabla + " WHERE escuela_id = :e")
					.param("e", escuelaA).query(Long.class).single()).as(tabla).isZero();
		}
	}
}
