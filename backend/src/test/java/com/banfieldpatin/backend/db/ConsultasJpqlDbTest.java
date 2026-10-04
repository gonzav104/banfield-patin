package com.banfieldpatin.backend.db;

import static com.banfieldpatin.backend.db.DatosDb.EN_1_DIA;
import static com.banfieldpatin.backend.db.DatosDb.HACE_1_DIA;
import static com.banfieldpatin.backend.db.DatosDb.HACE_1_HORA;
import static com.banfieldpatin.backend.db.DatosDb.HACE_2_DIAS;
import static com.banfieldpatin.backend.db.DatosDb.NULO;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.familias.Familia;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.invitaciones.EstadoInvitacion;
import com.banfieldpatin.backend.familias.invitaciones.GeneradorTokenInvitacion;
import com.banfieldpatin.backend.familias.invitaciones.Invitacion;
import com.banfieldpatin.backend.familias.invitaciones.InvitacionRepository;
import com.banfieldpatin.backend.usuarios.UsuarioRepository;

/**
 * Ejecuta de verdad las consultas JPQL y las actualizaciones condicionales que en la suite por defecto solo
 * existen como mocks: sintaxis, parametros, lower() y el filtro de estado derivado.
 */
@PruebaDb
class ConsultasJpqlDbTest extends BaseDbTest {

	@Autowired
	JdbcClient jdbc;
	@Autowired
	TransactionTemplate tx;
	@Autowired
	EscuelaRepository escuelas;
	@Autowired
	UsuarioRepository usuarios;
	@Autowired
	FamiliaRepository familias;
	@Autowired
	InvitacionRepository invitaciones;

	private DatosDb datos;
	private String slugA;
	private UUID escuelaA;
	private UUID escuelaB;
	private UUID familiaA;
	private UUID familiaB;
	private UUID adminA;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		slugA = "jpql-a-" + UUID.randomUUID();
		escuelaA = datos.escuela(slugA);
		escuelaB = datos.escuela("jpql-b-" + UUID.randomUUID());
		familiaA = datos.familia(escuelaA, "Los Gomez", true);
		familiaB = datos.familia(escuelaB, "Los Gomez B", true);
		adminA = datos.admin(escuelaA, "admin@a.example", true);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private static String hash() {
		return GeneradorTokenInvitacion.sha256Hex(UUID.randomUUID().toString());
	}

	// ---------- escuelas y usuarios ----------

	@Test
	void escuelaPorSlugSinDistinguirMayusculas() {
		assertThat(escuelas.findBySlugIgnoreCase(slugA.toUpperCase())).isPresent();
		assertThat(escuelas.findBySlugIgnoreCase("no-existe-" + UUID.randomUUID())).isEmpty();
	}

	@Test
	void escuelaConBloqueoPesimistaDentroDeUnaTransaccion() {
		var escuela = tx.execute(s -> escuelas.findBySlugParaActualizar(slugA.toUpperCase()));

		assertThat(escuela).isPresent();
		assertThat(escuela.get().getId()).isEqualTo(escuelaA);
	}

	@Test
	void usuarioPorEmailYExistenciaSinDistinguirMayusculasYPorEscuela() {
		datos.usuarioFamilia(escuelaA, familiaA, "Madre@A.example");

		assertThat(usuarios.buscarPorEmail(escuelaA, "madre@a.example")).isPresent();
		assertThat(usuarios.existeEmail(escuelaA, "MADRE@a.example")).isTrue();
		assertThat(usuarios.existeEmail(escuelaB, "madre@a.example")).isFalse();
		assertThat(usuarios.buscarPorEmail(escuelaB, "madre@a.example")).isEmpty();
	}

	@Test
	void cuentaSoloAdministradoresActivosDeLaEscuela() {
		datos.admin(escuelaA, "inactivo@a.example", false);
		datos.admin(escuelaB, "admin@b.example", true);
		datos.usuarioFamilia(escuelaA, familiaA, "madre@a.example");

		assertThat(usuarios.contarAdminsActivos(escuelaA)).isEqualTo(1);
	}

	// ---------- familias ----------

	@Test
	void buscarActivasFiltraPorNombreEscuelaYActiva() {
		datos.familia(escuelaA, "Gomez Inactiva", false);
		datos.familia(escuelaA, "Perez_Ana", true);
		datos.familia(escuelaA, "Perez Ana", true);

		List<String> gomez = nombres("gomez");
		assertThat(gomez).containsExactly("Los Gomez");

		// Sin filtro: solo activas de la escuela A, ordenadas por nombre sin distinguir mayusculas.
		assertThat(nombres("")).containsExactly("Los Gomez", "Perez Ana", "Perez_Ana");

		// El comodin '_' escapado con '!' es literal: solo coincide con el guion bajo.
		assertThat(nombres("!_")).containsExactly("Perez_Ana");
	}

	private List<String> nombres(String busqueda) {
		return familias.buscarActivas(escuelaA, busqueda, PageRequest.of(0, 10)).getContent().stream()
				.map(Familia::getNombreReferencia).toList();
	}

	// ---------- invitaciones ----------

	@Test
	void guardarYLeerUnaInvitacionPorLaEntidad() {
		String h = hash();
		Instant ahora = Instant.now();
		Invitacion guardada = invitaciones.save(Invitacion.crear(escuelaA, familiaA, h, "madre@example.com",
				ahora, ahora.plusSeconds(3600), adminA));

		assertThat(guardada.getId()).isNotNull();
		Invitacion leida = invitaciones.findByTokenHash(h).orElseThrow();
		assertThat(leida.getId()).isEqualTo(guardada.getId());
		assertThat(leida.getEmailSugerido()).isEqualTo("madre@example.com");
		assertThat(leida.getActualizadoEn()).isNotNull();
		assertThat(invitaciones.findByIdAndEscuelaId(guardada.getId(), escuelaA)).isPresent();
		assertThat(invitaciones.findByIdAndEscuelaId(guardada.getId(), escuelaB)).isEmpty();
	}

	@Test
	void listarFiltraPorEstadoDerivadoYOrdenaPorCreacionDescendente() {
		UUID usuario = datos.usuarioFamilia(escuelaA, familiaA, "madre@a.example");
		UUID pendiente = datos.invitacion(escuelaA, familiaA, adminA, hash(), HACE_1_HORA, EN_1_DIA, null, NULO, null, NULO);
		UUID expirada = datos.invitacion(escuelaA, familiaA, adminA, hash(), HACE_2_DIAS, HACE_1_DIA, null, NULO, null, NULO);
		UUID revocada = datos.invitacion(escuelaA, familiaA, adminA, hash(), "now() - interval '3 days'", EN_1_DIA,
				null, NULO, adminA, HACE_1_HORA);
		UUID usada = datos.invitacion(escuelaA, familiaA, adminA, hash(), "now() - interval '4 days'", EN_1_DIA,
				usuario, HACE_1_HORA, null, NULO);
		datos.invitacionPendiente(escuelaB, familiaB, datos.admin(escuelaB, "admin@b.example", true), hash());
		Instant ahora = Instant.now();

		assertThat(ids(EstadoInvitacion.PENDIENTE.name(), ahora)).containsExactly(pendiente);
		assertThat(ids(EstadoInvitacion.EXPIRADA.name(), ahora)).containsExactly(expirada);
		assertThat(ids(EstadoInvitacion.REVOCADA.name(), ahora)).containsExactly(revocada);
		assertThat(ids(EstadoInvitacion.USADA.name(), ahora)).containsExactly(usada);
		// TODOS: solo la escuela A y mas recientes primero.
		assertThat(ids(InvitacionRepository.TODOS, ahora)).containsExactly(pendiente, expirada, revocada, usada);
	}

	private List<UUID> ids(String estado, Instant ahora) {
		return invitaciones.listar(escuelaA, estado, ahora, PageRequest.of(0, 20)).getContent().stream()
				.map(Invitacion::getId).toList();
	}

	@Test
	void marcarRevocadaEsCondicionalYAcotadaALaEscuela() {
		UUID pendiente = datos.invitacionPendiente(escuelaA, familiaA, adminA, hash());
		Instant ahora = Instant.now();

		int otraEscuela = tx.execute(s -> invitaciones.marcarRevocada(pendiente, escuelaB, adminA, ahora));
		int primera = tx.execute(s -> invitaciones.marcarRevocada(pendiente, escuelaA, adminA, ahora));
		int repetida = tx.execute(s -> invitaciones.marcarRevocada(pendiente, escuelaA, adminA, ahora));

		assertThat(otraEscuela).isZero();
		assertThat(primera).isEqualTo(1);
		assertThat(repetida).isZero();
		assertThat(jdbc.sql("SELECT revocada_por FROM gestion_patin.invitacion WHERE id = :id")
				.param("id", pendiente).query(UUID.class).single()).isEqualTo(adminA);
	}

	@Test
	void marcarRevocadaNoAfectaAUnaInvitacionUsada() {
		UUID usuario = datos.usuarioFamilia(escuelaA, familiaA, "madre@a.example");
		UUID usada = datos.invitacion(escuelaA, familiaA, adminA, hash(), HACE_1_DIA, EN_1_DIA, usuario, HACE_1_HORA,
				null, NULO);

		int afectadas = tx.execute(s -> invitaciones.marcarRevocada(usada, escuelaA, adminA, Instant.now()));

		assertThat(afectadas).isZero();
	}

	@Test
	void marcarUsadaSoloAfectaUnaFilaPendienteYVigente() {
		UUID usuario = datos.usuarioFamilia(escuelaA, familiaA, "madre@a.example");
		UUID pendiente = datos.invitacionPendiente(escuelaA, familiaA, adminA, hash());
		UUID vencida = datos.invitacion(escuelaA, familiaA, adminA, hash(), HACE_2_DIAS, HACE_1_DIA, null, NULO, null, NULO);
		UUID revocada = datos.invitacion(escuelaA, familiaA, adminA, hash(), HACE_1_DIA, EN_1_DIA, null, NULO, adminA,
				HACE_1_HORA);
		Instant ahora = Instant.now();

		assertThat((int) tx.execute(s -> invitaciones.marcarUsada(vencida, usuario, ahora))).isZero();
		assertThat((int) tx.execute(s -> invitaciones.marcarUsada(revocada, usuario, ahora))).isZero();
		assertThat((int) tx.execute(s -> invitaciones.marcarUsada(pendiente, usuario, ahora))).isEqualTo(1);
		// Una segunda aplicacion no hace nada: ya esta usada.
		assertThat((int) tx.execute(s -> invitaciones.marcarUsada(pendiente, usuario, ahora))).isZero();

		assertThat(datos.usada(pendiente)).isTrue();
		assertThat(datos.usada(vencida)).isFalse();
		assertThat(datos.usada(revocada)).isFalse();
	}

	@Test
	void findByTokenHashParaActualizarBloqueaDentroDeUnaTransaccion() {
		String h = hash();
		datos.invitacionPendiente(escuelaA, familiaA, adminA, h);

		Invitacion inv = tx.execute(s -> invitaciones.findByTokenHashParaActualizar(h).orElseThrow());

		assertThat(inv.getEscuelaId()).isEqualTo(escuelaA);
	}
}
