package com.banfieldpatin.backend.db;

import static com.banfieldpatin.backend.db.DatosDb.HACE_1_HORA;
import static com.banfieldpatin.backend.db.DatosDb.NULO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Restricciones de V3 (usuario_mfa) contra PostgreSQL 17 real. Que el contexto arranque ya prueba que Flyway aplica
 * V1 + V2 + V3 en orden con el esquema real (no una simulacion).
 */
@PruebaDb
class V3ConstraintsDbTest extends BaseDbTest {

	/** 12 (nonce) + 20 (secreto) + 16 (etiqueta) = 48 bytes, el tamano real que produce el cifrador. */
	private static final byte[] SECRETO_CIFRADO = new byte[48];

	@Autowired
	JdbcClient jdbc;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UUID adminA;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("v3-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("v3-b-" + UUID.randomUUID());
		adminA = datos.admin(escuelaA, "admin@a.example", true);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private long filas(UUID usuarioId) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.usuario_mfa WHERE usuario_id = :u")
				.param("u", usuarioId).query(Long.class).single();
	}

	@Test
	void unFactorSinConfirmarSeInsertaYNoTienePasoUsado() {
		datos.usuarioMfa(adminA, escuelaA, SECRETO_CIFRADO, NULO, null);

		var fila = jdbc.sql("SELECT confirmado_en, ultimo_paso_usado FROM gestion_patin.usuario_mfa WHERE usuario_id = :u")
				.param("u", adminA).query().singleRow();
		assertThat(fila.get("confirmado_en")).isNull();
		assertThat(fila.get("ultimo_paso_usado")).isNull();
	}

	@Test
	void unFactorConfirmadoConPasoUsadoSeInserta() {
		datos.usuarioMfa(adminA, escuelaA, SECRETO_CIFRADO, HACE_1_HORA, 12_345_678L);

		assertThat(filas(adminA)).isEqualTo(1);
	}

	@Test
	void soloHayUnFactorPorUsuario() {
		datos.usuarioMfa(adminA, escuelaA, SECRETO_CIFRADO, NULO, null);

		assertThatThrownBy(() -> datos.usuarioMfa(adminA, escuelaA, SECRETO_CIFRADO, NULO, null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("usuario_mfa_pkey");
	}

	@Test
	void unaEscuelaDistintaALaDelUsuarioSeRechaza() {
		assertThatThrownBy(() -> datos.usuarioMfa(adminA, escuelaB, SECRETO_CIFRADO, NULO, null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_usuario_mfa_usuario_misma_escuela");
	}

	@Test
	void unUsuarioInexistenteSeRechaza() {
		assertThatThrownBy(() -> datos.usuarioMfa(UUID.randomUUID(), escuelaA, SECRETO_CIFRADO, NULO, null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_usuario_mfa_usuario_misma_escuela");
	}

	@Test
	void unSecretoDemasiadoCortoSeRechaza() {
		// Menos que nonce + etiqueta + 1 byte de texto cifrado: no puede ser salida de AES-GCM.
		assertThatThrownBy(() -> datos.usuarioMfa(adminA, escuelaA, new byte[28], NULO, null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_usuario_mfa_secreto_longitud");
	}

	@Test
	void unPasoNegativoSeRechaza() {
		assertThatThrownBy(() -> datos.usuarioMfa(adminA, escuelaA, SECRETO_CIFRADO, HACE_1_HORA, -1L))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_usuario_mfa_paso_no_negativo");
	}

	@Test
	void unPasoUsadoSinConfirmacionSeRechaza() {
		assertThatThrownBy(() -> datos.usuarioMfa(adminA, escuelaA, SECRETO_CIFRADO, NULO, 5L))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_usuario_mfa_paso_solo_si_confirmado");
	}

	@Test
	void elSecretoNoSePuedeOmitir() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO gestion_patin.usuario_mfa (usuario_id, escuela_id) VALUES (:u, :e)
				""").param("u", adminA).param("e", escuelaA).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("secreto_cifrado");
	}

	@Test
	void actualizarLaFilaRefrescaActualizadoEnPeroNoCreadoEn() throws Exception {
		datos.usuarioMfa(adminA, escuelaA, SECRETO_CIFRADO, NULO, null);
		var antes = jdbc.sql("SELECT creado_en, actualizado_en FROM gestion_patin.usuario_mfa WHERE usuario_id = :u")
				.param("u", adminA).query().singleRow();
		Thread.sleep(20);

		jdbc.sql("UPDATE gestion_patin.usuario_mfa SET confirmado_en = now() WHERE usuario_id = :u")
				.param("u", adminA).update();

		var despues = jdbc.sql("SELECT creado_en, actualizado_en FROM gestion_patin.usuario_mfa WHERE usuario_id = :u")
				.param("u", adminA).query().singleRow();
		assertThat(despues.get("creado_en")).isEqualTo(antes.get("creado_en"));
		assertThat(despues.get("actualizado_en").toString()).isNotEqualTo(antes.get("actualizado_en").toString());
	}

	@Test
	void noSePuedeBorrarUnUsuarioConFactor() {
		datos.usuarioMfa(adminA, escuelaA, SECRETO_CIFRADO, NULO, null);

		assertThatThrownBy(() -> jdbc.sql("DELETE FROM gestion_patin.usuario WHERE id = :u").param("u", adminA).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_usuario_mfa_usuario_misma_escuela");
	}

	@Test
	void flywayAplicoV1V2YV3EnOrden() {
		var versiones = jdbc.sql("""
				SELECT version FROM gestion_patin.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank
				""").query(String.class).list();

		assertThat(versiones).containsExactly("1", "2", "3");
	}
}
