package com.banfieldpatin.backend.db;

import static com.banfieldpatin.backend.db.DatosDb.EN_1_DIA;
import static com.banfieldpatin.backend.db.DatosDb.HACE_1_DIA;
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

import com.banfieldpatin.backend.escuelas.Escuela;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.familias.invitaciones.GeneradorTokenInvitacion;

import jakarta.persistence.EntityManager;

/**
 * Restricciones de V1 + V2 y mapeo JPA contra una PostgreSQL local descartable. Que el contexto arranque ya
 * prueba que Flyway aplica V1 y V2 y que Hibernate (ddl-auto=validate) acepta todas las entidades, incluidas
 * las columnas smallint mapeadas como Short.
 */
@PruebaDb
class V2ConstraintsDbTest extends BaseDbTest {

	@Autowired
	JdbcClient jdbc;
	@Autowired
	EntityManager em;
	@Autowired
	EscuelaRepository escuelas;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UUID familiaA;
	private UUID familiaB;
	private UUID adminA;
	private UUID adminB;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("v2-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("v2-b-" + UUID.randomUUID());
		familiaA = datos.familia(escuelaA, "Familia A", true);
		familiaB = datos.familia(escuelaB, "Familia B", true);
		adminA = datos.admin(escuelaA, "admin@a.example", true);
		adminB = datos.admin(escuelaB, "admin@b.example", true);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private static String hash() {
		return GeneradorTokenInvitacion.sha256Hex(UUID.randomUUID().toString());
	}

	// ---------- mapeo / validate ----------

	@Test
	void hibernateValidaTodasLasEntidadesContraV1YV2() {
		var tipos = em.getMetamodel().getEntities().stream().map(e -> e.getJavaType().getSimpleName()).toList();

		assertThat(tipos).contains("Escuela", "Familia", "Usuario", "Invitacion");
	}

	@Test
	void lasColumnasSmallintSeLeenComoShort() {
		Escuela escuela = escuelas.findById(escuelaA).orElseThrow();

		assertThat(escuela.getMaxAdministradores()).isInstanceOf(Short.class).isEqualTo((short) 2);
	}

	// ---------- CHECK ----------

	@Test
	void usadaYRevocadaALaVezSeRechaza() {
		UUID usuario = datos.usuarioFamilia(escuelaA, familiaA, "madre@a.example");

		assertThatThrownBy(() -> datos.invitacion(escuelaA, familiaA, adminA, hash(), HACE_1_DIA, EN_1_DIA,
				usuario, HACE_1_HORA, adminA, HACE_1_HORA))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_invitacion_usada_o_revocada");
	}

	@Test
	void usoIncoherenteSeRechaza() {
		// usado_en sin usuario_id
		assertThatThrownBy(() -> datos.invitacion(escuelaA, familiaA, adminA, hash(), HACE_1_DIA, EN_1_DIA,
				null, HACE_1_HORA, null, NULO))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_invitacion_uso_coherente");
		// usuario_id sin usado_en
		UUID usuario = datos.usuarioFamilia(escuelaA, familiaA, "otra@a.example");
		assertThatThrownBy(() -> datos.invitacion(escuelaA, familiaA, adminA, hash(), HACE_1_DIA, EN_1_DIA,
				usuario, NULO, null, NULO))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_invitacion_uso_coherente");
	}

	@Test
	void expiracionNoPosteriorALaCreacionSeRechaza() {
		assertThatThrownBy(() -> datos.invitacion(escuelaA, familiaA, adminA, hash(), HACE_1_HORA, HACE_1_DIA,
				null, NULO, null, NULO))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_invitacion_expiracion");
	}

	@Test
	void revocacionIncoherenteSeRechaza() {
		assertThatThrownBy(() -> datos.invitacion(escuelaA, familiaA, adminA, hash(), HACE_1_DIA, EN_1_DIA,
				null, NULO, null, HACE_1_HORA))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_invitacion_revocacion_coherente");
	}

	@Test
	void tokenHashConFormatoInvalidoSeRechaza() {
		assertThatThrownBy(() -> datos.invitacionPendiente(escuelaA, familiaA, adminA, "token-en-claro"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_invitacion_token_hash_formato");
	}

	// ---------- FK compuestas por escuela ----------

	@Test
	void familiaDeOtraEscuelaSeRechazaPorLaFkCompuesta() {
		assertThatThrownBy(() -> datos.invitacionPendiente(escuelaA, familiaB, adminA, hash()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_invitacion_familia_misma_escuela");
	}

	@Test
	void creadorDeOtraEscuelaSeRechazaPorLaFkCompuesta() {
		assertThatThrownBy(() -> datos.invitacionPendiente(escuelaA, familiaA, adminB, hash()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_invitacion_creada_por_misma_escuela");
	}

	@Test
	void usuarioRegistradoDeOtraEscuelaSeRechazaPorLaFkCompuesta() {
		UUID usuarioB = datos.usuarioFamilia(escuelaB, familiaB, "madre@b.example");

		assertThatThrownBy(() -> datos.invitacion(escuelaA, familiaA, adminA, hash(), HACE_1_DIA, EN_1_DIA,
				usuarioB, HACE_1_HORA, null, NULO))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_invitacion_usuario_misma_escuela");
	}

	// ---------- unicidad ----------

	@Test
	void tokenHashDuplicadoSeRechaza() {
		String igual = hash();
		datos.invitacionPendiente(escuelaA, familiaA, adminA, igual);

		assertThatThrownBy(() -> datos.invitacionPendiente(escuelaA, familiaA, adminA, igual))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_invitacion_token_hash");
	}

	@Test
	void unUsuarioNoPuedeConsumirDosInvitaciones() {
		UUID usuario = datos.usuarioFamilia(escuelaA, familiaA, "madre@a.example");
		datos.invitacion(escuelaA, familiaA, adminA, hash(), HACE_1_DIA, EN_1_DIA, usuario, HACE_1_HORA, null, NULO);

		assertThatThrownBy(() -> datos.invitacion(escuelaA, familiaA, adminA, hash(), HACE_1_DIA, EN_1_DIA,
				usuario, HACE_1_HORA, null, NULO))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_invitacion_usuario");
	}

	@Test
	void elEmailEsUnicoPorEscuelaSinDistinguirMayusculas() {
		datos.usuarioFamilia(escuelaA, familiaA, "Madre@A.example");

		assertThatThrownBy(() -> datos.usuarioFamilia(escuelaA, familiaA, "madre@a.EXAMPLE"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_usuario_email_escuela");
		// Otra escuela puede reutilizar el mismo email.
		assertThat(datos.usuarioFamilia(escuelaB, familiaB, "madre@a.example")).isNotNull();
	}

	@Test
	void unAdminNoPuedeTenerFamilia() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO gestion_patin.usuario (escuela_id, familia_id, nombre, apellido, email, password_hash, rol)
				VALUES (:e, :f, 'N', 'A', 'x@a.example', 'h', 'ADMIN')
				""").param("e", escuelaA).param("f", familiaA).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_usuario_familia_por_rol");
	}
}
