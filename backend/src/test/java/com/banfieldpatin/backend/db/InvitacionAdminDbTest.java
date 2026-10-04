package com.banfieldpatin.backend.db;

import static com.banfieldpatin.backend.db.DatosDb.EN_1_DIA;
import static com.banfieldpatin.backend.db.DatosDb.HACE_1_DIA;
import static com.banfieldpatin.backend.db.DatosDb.HACE_1_HORA;
import static com.banfieldpatin.backend.db.DatosDb.HACE_2_DIAS;
import static com.banfieldpatin.backend.db.DatosDb.NULO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.invitaciones.EstadoInvitacion;
import com.banfieldpatin.backend.familias.invitaciones.GeneradorTokenInvitacion;
import com.banfieldpatin.backend.familias.invitaciones.InvitacionAdminService;
import com.banfieldpatin.backend.familias.invitaciones.dto.CrearInvitacionSolicitud;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionCreadaRespuesta;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionRespuesta;
import com.banfieldpatin.backend.familias.invitaciones.dto.NuevaFamiliaSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import tools.jackson.databind.json.JsonMapper;

/**
 * Alta, listado y revocacion de invitaciones por un ADMIN contra PostgreSQL real: beans y proxies transaccionales
 * reales, Flyway V1 + V2, auditoria con jsonb/inet de verdad y sin mocks de repositorios.
 */
@PruebaDb
@Import(InvitacionAdminDbTest.Config.class)
class InvitacionAdminDbTest extends BaseDbTest {

	private static final DatosSolicitud DATOS = new DatosSolicitud("10.0.0.7", "JUnit");

	@TestConfiguration
	@Import({ InvitacionAdminService.class, AuditoriaService.class, GeneradorTokenInvitacion.class })
	static class Config {

		@Bean
		Clock reloj() {
			return Clock.systemUTC();
		}

		@Bean
		JsonMapper jsonMapper() {
			return JsonMapper.builder().build();
		}
	}

	@Autowired
	JdbcClient jdbc;
	@Autowired
	InvitacionAdminService servicio;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UUID familiaA;
	private UUID adminId;
	private UsuarioAutenticado admin;
	private UsuarioAutenticado adminDeB;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("inv-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("inv-b-" + UUID.randomUUID());
		familiaA = datos.familia(escuelaA, "Los Gomez", true);
		adminId = datos.admin(escuelaA, "admin@a.example", true);
		UUID adminB = datos.admin(escuelaB, "admin@b.example", true);
		admin = new UsuarioAutenticado(adminId, escuelaA, null, Rol.ADMIN);
		adminDeB = new UsuarioAutenticado(adminB, escuelaB, null, Rol.ADMIN);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private static CrearInvitacionSolicitud paraFamilia(UUID familiaId, String email, Integer dias) {
		return new CrearInvitacionSolicitud(familiaId, null, email, dias);
	}

	private static CrearInvitacionSolicitud conFamiliaNueva(String nombre, String email, Integer dias) {
		return new CrearInvitacionSolicitud(null, new NuevaFamiliaSolicitud(nombre), email, dias);
	}

	private long contar(String tabla, UUID escuela) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin." + tabla + " WHERE escuela_id = :e").param("e", escuela)
				.query(Long.class).single();
	}

	private long auditorias(UUID escuela, String accion) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = :a")
				.param("e", escuela).param("a", accion).query(Long.class).single();
	}

	private static String hash() {
		return GeneradorTokenInvitacion.sha256Hex(UUID.randomUUID().toString());
	}

	private static ExcepcionNegocio fallo(Runnable accion) {
		try {
			accion.run();
		} catch (ExcepcionNegocio e) {
			return e;
		}
		throw new AssertionError("Se esperaba ExcepcionNegocio");
	}

	// ---------- crear ----------

	@Test
	void creaInvitacionParaFamiliaExistenteConVigenciaPorDefectoDe7Dias() {
		Instant antes = Instant.now();

		InvitacionCreadaRespuesta r = servicio.crear(admin, paraFamilia(familiaA, "Madre@Gomez.example", null), DATOS);

		assertThat(r.estado()).isEqualTo(EstadoInvitacion.PENDIENTE);
		assertThat(r.familia().id()).isEqualTo(familiaA);
		assertThat(r.emailSugerido()).isEqualTo("madre@gomez.example");
		assertThat(r.token()).isNotBlank();
		assertThat(Duration.between(antes, r.expiraEn())).isBetween(Duration.ofDays(7).minusSeconds(5),
				Duration.ofDays(7).plusSeconds(5));
		var fila = jdbc.sql("""
				SELECT escuela_id, familia_id, creada_por, email_sugerido, expira_en, usado_en, revocada_en
				FROM gestion_patin.invitacion WHERE id = :id
				""").param("id", r.id()).query().singleRow();
		assertThat(fila.get("escuela_id")).isEqualTo(escuelaA);
		assertThat(fila.get("familia_id")).isEqualTo(familiaA);
		assertThat(fila.get("creada_por")).isEqualTo(adminId);
		assertThat(fila.get("email_sugerido")).isEqualTo("madre@gomez.example");
		assertThat(fila.get("usado_en")).isNull();
		assertThat(fila.get("revocada_en")).isNull();
		assertThat(contar("familia", escuelaA)).isEqualTo(1);
		assertThat(auditorias(escuelaA, "FAMILIA_CREADA")).isZero();
		assertThat(auditorias(escuelaA, "INVITACION_CREADA")).isEqualTo(1);
	}

	@Test
	void soloSePersisteElHashDelTokenYElTokenEnClaroNoApareceEnNingunaColumna() {
		InvitacionCreadaRespuesta r = servicio.crear(admin, paraFamilia(familiaA, "a@gomez.example", null), DATOS);

		String hashGuardado = jdbc.sql("SELECT token_hash FROM gestion_patin.invitacion WHERE id = :id")
				.param("id", r.id()).query(String.class).single();
		assertThat(hashGuardado).isEqualTo(GeneradorTokenInvitacion.sha256Hex(r.token())).isNotEqualTo(r.token());
		// El token en claro no esta en ninguna columna de ninguna tabla de la escuela.
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.invitacion i WHERE i::text LIKE :t")
				.param("t", "%" + r.token() + "%").query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria a WHERE a::text LIKE :t")
				.param("t", "%" + r.token() + "%").query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.familia f WHERE f::text LIKE :t")
				.param("t", "%" + r.token() + "%").query(Long.class).single()).isZero();
	}

	@Test
	void creaFamiliaEnLineaYLaInvitacionEnLaMismaTransaccionYAudita() {
		InvitacionCreadaRespuesta r = servicio.crear(admin, conFamiliaNueva("  Los Perez  ", null, 10), DATOS);

		assertThat(r.familia().nombreReferencia()).isEqualTo("Los Perez");
		assertThat(contar("familia", escuelaA)).isEqualTo(2);
		UUID familiaNueva = r.familia().id();
		assertThat(jdbc.sql("SELECT familia_id FROM gestion_patin.invitacion WHERE id = :id").param("id", r.id())
				.query(UUID.class).single()).isEqualTo(familiaNueva);
		assertThat(jdbc.sql("SELECT activa FROM gestion_patin.familia WHERE id = :id").param("id", familiaNueva)
				.query(Boolean.class).single()).isTrue();

		var familiaCreada = jdbc.sql("""
				SELECT usuario_id, recurso_tipo, recurso_id, host(ip) AS ip, user_agent, detalle::text AS detalle
				FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'FAMILIA_CREADA'
				""").param("e", escuelaA).query().singleRow();
		assertThat(familiaCreada.get("usuario_id")).isEqualTo(adminId);
		assertThat(familiaCreada.get("recurso_tipo")).isEqualTo("FAMILIA");
		assertThat(familiaCreada.get("recurso_id")).isEqualTo(familiaNueva);
		assertThat(familiaCreada.get("ip")).isEqualTo("10.0.0.7");
		assertThat(familiaCreada.get("user_agent")).isEqualTo("JUnit");
		assertThat(jdbc.sql("SELECT detalle->>'origen' FROM gestion_patin.auditoria WHERE accion = 'FAMILIA_CREADA' AND escuela_id = :e")
				.param("e", escuelaA).query(String.class).single()).isEqualTo("INVITACION");

		var invitacionCreada = jdbc.sql("""
				SELECT recurso_tipo, recurso_id, detalle::text AS detalle,
				       detalle->>'familiaId' AS familia_id, detalle->>'familiaCreada' AS familia_creada,
				       detalle->>'conEmailSugerido' AS con_email
				FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'INVITACION_CREADA'
				""").param("e", escuelaA).query().singleRow();
		assertThat(invitacionCreada.get("recurso_tipo")).isEqualTo("INVITACION");
		assertThat(invitacionCreada.get("recurso_id")).isEqualTo(r.id());
		assertThat(invitacionCreada.get("familia_id")).isEqualTo(familiaNueva.toString());
		assertThat(invitacionCreada.get("familia_creada")).isEqualTo("true");
		assertThat(invitacionCreada.get("con_email")).isEqualTo("false");
		assertThat((String) invitacionCreada.get("detalle")).doesNotContain(r.token())
				.doesNotContain(GeneradorTokenInvitacion.sha256Hex(r.token()));
	}

	@Test
	void siFallaLaInvitacionSeRevierteTambienLaFamiliaCreadaEnLineaYSuAuditoria() {
		// 200 caracteres: pasa la logica del servicio pero viola varchar(180) al volcar el INSERT.
		String emailDemasiadoLargo = "x".repeat(190) + "@a.example";

		assertThatThrownBy(() -> servicio.crear(admin, conFamiliaNueva("Los Rollback", emailDemasiadoLargo, null), DATOS))
				.isInstanceOf(RuntimeException.class);

		assertThat(contar("familia", escuelaA)).isEqualTo(1);
		assertThat(contar("invitacion", escuelaA)).isZero();
		assertThat(auditorias(escuelaA, "FAMILIA_CREADA")).isZero();
		assertThat(auditorias(escuelaA, "INVITACION_CREADA")).isZero();
	}

	@Test
	void vigenciaMaximaDe30DiasSeAceptaYMasDeLaMaximaOMenosDe1SeRechaza() {
		InvitacionCreadaRespuesta r = servicio.crear(admin, paraFamilia(familiaA, null, 30), DATOS);
		assertThat(Duration.between(Instant.now(), r.expiraEn())).isBetween(Duration.ofDays(30).minusSeconds(5),
				Duration.ofDays(30).plusSeconds(5));

		for (int dias : new int[] { 31, 0, -3 }) {
			ExcepcionNegocio e = fallo(() -> servicio.crear(admin, paraFamilia(familiaA, null, dias), DATOS));
			assertThat(e.getCodigo()).as("dias=%d", dias).isEqualTo("VALIDACION");
		}
		assertThat(contar("invitacion", escuelaA)).isEqualTo(1);
	}

	@Test
	void unaVigenciaInvalidaConFamiliaEnLineaNoDejaNingunaFilaNiAuditoria() {
		ExcepcionNegocio e = fallo(() -> servicio.crear(admin, conFamiliaNueva("Los Invalidos", null, 31), DATOS));

		assertThat(e.getCodigo()).isEqualTo("VALIDACION");
		assertThat(contar("familia", escuelaA)).isEqualTo(1);
		assertThat(contar("invitacion", escuelaA)).isZero();
		assertThat(auditorias(escuelaA, "FAMILIA_CREADA")).isZero();
	}

	@Test
	void familiaDeOtraEscuelaInexistenteOInactivaDaFamiliaNoEncontradaSinCrearNada() {
		UUID deB = datos.familia(escuelaB, "Los de B", true);
		UUID inactiva = datos.familia(escuelaA, "Inactiva", false);

		for (UUID id : List.of(deB, inactiva, UUID.randomUUID())) {
			ExcepcionNegocio e = fallo(() -> servicio.crear(admin, paraFamilia(id, null, null), DATOS));
			assertThat(e.getCodigo()).isEqualTo("FAMILIA_NO_ENCONTRADA");
			assertThat(e.getEstado().value()).isEqualTo(404);
		}
		assertThat(contar("invitacion", escuelaA)).isZero();
		assertThat(contar("invitacion", escuelaB)).isZero();
		assertThat(auditorias(escuelaA, "INVITACION_CREADA")).isZero();
	}

	@Test
	void indicarAmbasOningunaFamiliaDa400() {
		ExcepcionNegocio ambas = fallo(() -> servicio.crear(admin,
				new CrearInvitacionSolicitud(familiaA, new NuevaFamiliaSolicitud("X"), null, null), DATOS));
		ExcepcionNegocio ninguna = fallo(() -> servicio.crear(admin, new CrearInvitacionSolicitud(null, null, null, null), DATOS));

		assertThat(ambas.getCodigo()).isEqualTo("VALIDACION");
		assertThat(ninguna.getCodigo()).isEqualTo("VALIDACION");
	}

	// ---------- listar y obtener ----------

	@Test
	void listaFiltraPorEstadoPaginaYAcotaPorEscuela() {
		UUID usuarioFamilia = datos.usuarioFamilia(escuelaA, familiaA, "madre@a.example");
		UUID pendiente = datos.invitacion(escuelaA, familiaA, adminId, hash(), "now() - interval '3 hours'", EN_1_DIA,
				null, NULO, null, NULO);
		UUID pendiente2 = datos.invitacion(escuelaA, familiaA, adminId, hash(), "now() - interval '2 hours'", EN_1_DIA,
				null, NULO, null, NULO);
		UUID expirada = datos.invitacion(escuelaA, familiaA, adminId, hash(), HACE_2_DIAS, HACE_1_DIA, null, NULO,
				null, NULO);
		UUID usada = datos.invitacion(escuelaA, familiaA, adminId, hash(), "now() - interval '5 hours'", EN_1_DIA,
				usuarioFamilia, HACE_1_HORA, null, NULO);
		UUID revocada = datos.invitacion(escuelaA, familiaA, adminId, hash(), "now() - interval '6 hours'", EN_1_DIA,
				null, NULO, adminId, HACE_1_HORA);
		// De otra escuela: nunca debe verse.
		datos.invitacionPendiente(escuelaB, datos.familia(escuelaB, "B", true), adminDeB.id(), hash());

		assertThat(ids(servicio.listar(admin, EstadoInvitacion.PENDIENTE, PageRequest.of(0, 20))))
				.containsExactly(pendiente2, pendiente);
		assertThat(ids(servicio.listar(admin, EstadoInvitacion.EXPIRADA, PageRequest.of(0, 20)))).containsExactly(expirada);
		assertThat(ids(servicio.listar(admin, EstadoInvitacion.USADA, PageRequest.of(0, 20)))).containsExactly(usada);
		assertThat(ids(servicio.listar(admin, EstadoInvitacion.REVOCADA, PageRequest.of(0, 20)))).containsExactly(revocada);

		Pagina<InvitacionRespuesta> todas = servicio.listar(admin, null, PageRequest.of(0, 20));
		assertThat(todas.totalElementos()).isEqualTo(5);
		assertThat(ids(todas)).containsExactlyInAnyOrder(pendiente, pendiente2, expirada, usada, revocada);
		assertThat(todas.contenido()).allSatisfy(i -> assertThat(i.familia().nombreReferencia()).isEqualTo("Los Gomez"));

		// Paginacion: mas recientes primero (creado_en desc): pendiente2, pendiente, expirada(-2d)... usada/revocada son mas recientes que expirada.
		Pagina<InvitacionRespuesta> p0 = servicio.listar(admin, null, PageRequest.of(0, 2));
		Pagina<InvitacionRespuesta> p1 = servicio.listar(admin, null, PageRequest.of(1, 2));
		Pagina<InvitacionRespuesta> p2 = servicio.listar(admin, null, PageRequest.of(2, 2));
		assertThat(ids(p0)).containsExactly(pendiente2, pendiente);
		assertThat(ids(p1)).containsExactly(usada, revocada);
		assertThat(ids(p2)).containsExactly(expirada);
		assertThat(p0.totalElementos()).isEqualTo(5);

		// Scoping: la otra escuela solo ve la suya.
		assertThat(servicio.listar(adminDeB, null, PageRequest.of(0, 20)).totalElementos()).isEqualTo(1);
	}

	private static List<UUID> ids(Pagina<InvitacionRespuesta> pagina) {
		return pagina.contenido().stream().map(InvitacionRespuesta::id).toList();
	}

	@Test
	void obtenerEstaAcotadoPorEscuela() {
		UUID id = datos.invitacionPendiente(escuelaA, familiaA, adminId, hash());

		InvitacionRespuesta r = servicio.obtener(admin, id);

		assertThat(r.id()).isEqualTo(id);
		assertThat(r.estado()).isEqualTo(EstadoInvitacion.PENDIENTE);
		assertThat(r.familia().nombreReferencia()).isEqualTo("Los Gomez");
		ExcepcionNegocio ajena = fallo(() -> servicio.obtener(adminDeB, id));
		assertThat(ajena.getCodigo()).isEqualTo("INVITACION_NO_ENCONTRADA");
		assertThat(ajena.getEstado().value()).isEqualTo(404);
		assertThat(fallo(() -> servicio.obtener(admin, UUID.randomUUID())).getCodigo())
				.isEqualTo("INVITACION_NO_ENCONTRADA");
	}

	// ---------- revocar ----------

	@Test
	void revocarUnaPendienteLaMarcaConRevocadaPorYAudita() {
		UUID id = datos.invitacionPendiente(escuelaA, familiaA, adminId, hash());

		InvitacionRespuesta r = servicio.revocar(admin, id, DATOS);

		assertThat(r.estado()).isEqualTo(EstadoInvitacion.REVOCADA);
		assertThat(r.revocadaEn()).isNotNull();
		var fila = jdbc.sql("SELECT revocada_por, revocada_en FROM gestion_patin.invitacion WHERE id = :id")
				.param("id", id).query().singleRow();
		assertThat(fila.get("revocada_por")).isEqualTo(adminId);
		assertThat(fila.get("revocada_en")).isNotNull();
		var audit = jdbc.sql("""
				SELECT usuario_id, recurso_tipo, recurso_id, detalle->>'familiaId' AS familia_id
				FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'INVITACION_REVOCADA'
				""").param("e", escuelaA).query().singleRow();
		assertThat(audit.get("usuario_id")).isEqualTo(adminId);
		assertThat(audit.get("recurso_tipo")).isEqualTo("INVITACION");
		assertThat(audit.get("recurso_id")).isEqualTo(id);
		assertThat(audit.get("familia_id")).isEqualTo(familiaA.toString());
	}

	@Test
	void revocarUnaExpiradaSinUsarEstaPermitido() {
		UUID id = datos.invitacion(escuelaA, familiaA, adminId, hash(), HACE_2_DIAS, HACE_1_DIA, null, NULO, null, NULO);

		InvitacionRespuesta r = servicio.revocar(admin, id, DATOS);

		assertThat(r.estado()).isEqualTo(EstadoInvitacion.REVOCADA);
		assertThat(auditorias(escuelaA, "INVITACION_REVOCADA")).isEqualTo(1);
	}

	@Test
	void revocarUnaYaRevocadaEsIdempotenteConservaLosDatosOriginalesYNoAudita() {
		UUID otroAdmin = datos.admin(escuelaA, "otro-admin@a.example", true);
		UUID id = datos.invitacion(escuelaA, familiaA, otroAdmin, hash(), HACE_2_DIAS, EN_1_DIA, null, NULO, otroAdmin,
				"now() - interval '3 hours'");
		var antes = jdbc.sql("SELECT revocada_en, revocada_por FROM gestion_patin.invitacion WHERE id = :id")
				.param("id", id).query().singleRow();

		InvitacionRespuesta r = servicio.revocar(admin, id, DATOS);

		assertThat(r.estado()).isEqualTo(EstadoInvitacion.REVOCADA);
		var despues = jdbc.sql("SELECT revocada_en, revocada_por FROM gestion_patin.invitacion WHERE id = :id")
				.param("id", id).query().singleRow();
		assertThat(despues).isEqualTo(antes);
		assertThat(despues.get("revocada_por")).isEqualTo(otroAdmin);
		assertThat(auditorias(escuelaA, "INVITACION_REVOCADA")).isZero();
	}

	@Test
	void revocarUnaUsadaDa409NoRevocable() {
		UUID usuario = datos.usuarioFamilia(escuelaA, familiaA, "madre@a.example");
		UUID id = datos.invitacion(escuelaA, familiaA, adminId, hash(), HACE_2_DIAS, EN_1_DIA, usuario, HACE_1_HORA,
				null, NULO);

		ExcepcionNegocio e = fallo(() -> servicio.revocar(admin, id, DATOS));

		assertThat(e.getCodigo()).isEqualTo("INVITACION_NO_REVOCABLE");
		assertThat(e.getEstado().value()).isEqualTo(409);
		assertThat(jdbc.sql("SELECT revocada_en IS NULL FROM gestion_patin.invitacion WHERE id = :id").param("id", id)
				.query(Boolean.class).single()).isTrue();
		assertThat(auditorias(escuelaA, "INVITACION_REVOCADA")).isZero();
	}

	@Test
	void revocarUnaInvitacionDeOtraEscuelaDa404YNoLaToca() {
		UUID deB = datos.invitacionPendiente(escuelaB, datos.familia(escuelaB, "B", true), adminDeB.id(), hash());

		ExcepcionNegocio e = fallo(() -> servicio.revocar(admin, deB, DATOS));

		assertThat(e.getCodigo()).isEqualTo("INVITACION_NO_ENCONTRADA");
		assertThat(e.getEstado().value()).isEqualTo(404);
		assertThat(jdbc.sql("SELECT revocada_en IS NULL FROM gestion_patin.invitacion WHERE id = :id").param("id", deB)
				.query(Boolean.class).single()).isTrue();
		assertThat(auditorias(escuelaA, "INVITACION_REVOCADA")).isZero();
		assertThat(auditorias(escuelaB, "INVITACION_REVOCADA")).isZero();
	}
}
