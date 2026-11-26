package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.responsables.ResponsableAlta;
import com.banfieldpatin.backend.familias.responsables.ResponsableRevision;
import com.banfieldpatin.backend.familias.responsables.ResponsableTipo;
import com.banfieldpatin.backend.familias.responsables.ResponsablesAdminService;
import com.banfieldpatin.backend.familias.responsables.TipoVinculoTutor;
import com.banfieldpatin.backend.familias.tutores.TutorAdminService;
import com.banfieldpatin.backend.familias.vinculos.VinculoAdminService;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/** Servicios reales con PostgreSQL: alta idempotente, edición, revocación, aislamiento y carreras con las bajas de C2-A. */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = "banfield.responsables.alta-habilitada=true")
class ResponsablesServiciosDbTest extends BaseDbTest {
	private static final DatosSolicitud DATOS = new DatosSolicitud(null, "JUnit");
	private static final ExecutorService HILOS = Executors.newCachedThreadPool();

	@Autowired JdbcClient jdbc;
	@Autowired ResponsablesAdminService responsables;
	@Autowired FamiliaAdminService familias;
	@Autowired DeportistaAdminService deportistas;
	@Autowired VinculoAdminService vinculos;
	@Autowired TutorAdminService tutores;
	@Autowired PlatformTransactionManager manager;

	@AfterAll
	static void cerrar() {
		HILOS.shutdownNow();
	}

	// ---------- alta, revisiones e idempotencia ----------

	@Test
	void altaCreaConAuditoriaYNoMueveLaRevisionDeVinculos() {
		var a = caso();
		var t = tutor(a);
		long vinculosAntes = revisionVinculos(a);
		var r = responsables.crear(a.admin(), a.deportista(), UUID.randomUUID(), alta(a, t, null));
		assertThat(r.creada()).isTrue();
		assertThat(r.respuesta().tipo()).isNull();
		assertThat(r.respuesta().cerradoEn()).isNull();
		assertThat(auditorias(r.respuesta().id(), "VIGENCIA_TUTOR_CREADA")).isEqualTo(1);
		assertThat(revisionVinculos(a)).isEqualTo(vinculosAntes);
	}

	@ParameterizedTest
	@EnumSource(Revision.class)
	void cadaRevisionObsoletaDaConflictoSinEscribirNiAuditar(Revision revision) {
		var a = caso();
		var t = tutor(a);
		var actual = alta(a, t, TipoVinculoTutor.MADRE);
		var obsoleta = revision.obsoleta(actual);
		assertThatThrownBy(() -> responsables.crear(a.admin(), a.deportista(), UUID.randomUUID(), obsoleta))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
					assertThat(e.getCodigo()).isEqualTo("EDICION_DESACTUALIZADA");
					assertThat(e.getDetalles()).extracting("campo").containsExactly(revision.campo);
				});
		assertThat(vigencias(a, t)).isZero();
	}

	@Test
	void repeticionDevuelveLaOriginalAunCerradaYConOtraRevisionObservada() {
		var a = caso();
		var t = tutor(a);
		var clave = UUID.randomUUID();
		var pedido = alta(a, t, TipoVinculoTutor.PADRE);
		var primera = responsables.crear(a.admin(), a.deportista(), clave, pedido);
		var repetida = responsables.crear(a.admin(), a.deportista(), clave, pedido);
		assertThat(repetida.creada()).isFalse();
		assertThat(repetida.respuesta().id()).isEqualTo(primera.respuesta().id());
		assertThat(auditorias(primera.respuesta().id(), "VIGENCIA_TUTOR_CREADA")).isEqualTo(1);

		responsables.revocar(a.admin(), a.deportista(), primera.respuesta().id(),
				new ResponsableRevision(Long.valueOf(primera.respuesta().version())));
		// Repetición tardía con revisiones que ya no son las vigentes: la original, cerrada, sin crear otra.
		var tardia = responsables.crear(a.admin(), a.deportista(), clave, pedido);
		assertThat(tardia.creada()).isFalse();
		assertThat(tardia.respuesta().id()).isEqualTo(primera.respuesta().id());
		assertThat(tardia.respuesta().cerradoEn()).isNotNull();
		assertThat(vigencias(a, t)).isEqualTo(1);

		assertThatThrownBy(() -> responsables.crear(a.admin(), a.deportista(), clave, alta(a, t, TipoVinculoTutor.MADRE)))
				.isInstanceOfSatisfying(ExcepcionNegocio.class,
						e -> assertThat(e.getCodigo()).isEqualTo("IDEMPOTENCY_KEY_REUTILIZADA"));
		var nueva = responsables.crear(a.admin(), a.deportista(), UUID.randomUUID(), alta(a, t, TipoVinculoTutor.PADRE));
		assertThat(nueva.creada()).isTrue();
		assertThat(nueva.respuesta().id()).isNotEqualTo(primera.respuesta().id());
		assertThat(vigencias(a, t)).isEqualTo(2);
	}

	@Test
	void unaSegundaClaveParaElMismoParVigenteEsConflictoYFamiliaNoEscribe() {
		var a = caso();
		var t = tutor(a);
		responsables.crear(a.admin(), a.deportista(), UUID.randomUUID(), alta(a, t, null));
		assertThatThrownBy(() -> responsables.crear(a.admin(), a.deportista(), UUID.randomUUID(), alta(a, t, null)))
				.isInstanceOfSatisfying(ExcepcionNegocio.class,
						e -> assertThat(e.getCodigo()).isEqualTo("RESPONSABLE_YA_VIGENTE"));
		var familiar = new UsuarioAutenticado(new DatosDb(jdbc).usuarioFamilia(a.admin().escuelaId(), a.familia(),
				"familia-" + UUID.randomUUID() + "@example.invalid"), a.admin().escuelaId(), a.familia(), Rol.FAMILIA);
		assertThatThrownBy(() -> responsables.crear(familiar, a.deportista(), UUID.randomUUID(), alta(a, tutor(a), null)))
				.isInstanceOf(AccessDeniedException.class);
		assertThat(vigencias(a, t)).isEqualTo(1);
	}

	@Test
	void aislamientoEntreEscuelasYClavesIndependientes() {
		var a = caso();
		var b = caso();
		var t = tutor(a);
		var clave = UUID.randomUUID();
		var deA = responsables.crear(a.admin(), a.deportista(), clave, alta(a, t, null));
		// ADMIN de otra escuela: el deportista, el tutor y la vigencia son indistinguibles de inexistentes.
		assertThatThrownBy(() -> responsables.crear(b.admin(), a.deportista(), clave, alta(a, t, null)))
				.isInstanceOfSatisfying(ExcepcionNegocio.class,
						e -> assertThat(e.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO"));
		assertThatThrownBy(() -> responsables.crear(b.admin(), b.deportista(), UUID.randomUUID(), alta(a, t, null)))
				.isInstanceOfSatisfying(ExcepcionNegocio.class,
						e -> assertThat(e.getCodigo()).isEqualTo("TUTOR_NO_ENCONTRADO"));
		assertThatThrownBy(() -> responsables.revocar(b.admin(), a.deportista(), deA.respuesta().id(),
				new ResponsableRevision(0L))).isInstanceOfSatisfying(ExcepcionNegocio.class,
						e -> assertThat(e.getCodigo()).isEqualTo("RESPONSABLE_NO_ENCONTRADO"));
		assertThatThrownBy(() -> responsables.actualizarTipo(b.admin(), b.deportista(), deA.respuesta().id(),
				new ResponsableTipo(TipoVinculoTutor.MADRE, 0L))).isInstanceOfSatisfying(ExcepcionNegocio.class,
						e -> assertThat(e.getCodigo()).isEqualTo("RESPONSABLE_NO_ENCONTRADO"));
		// La misma clave en la escuela B es independiente y crea su propia vigencia.
		var tB = tutor(b);
		var deB = responsables.crear(b.admin(), b.deportista(), clave, alta(b, tB, null));
		assertThat(deB.creada()).isTrue();
		assertThat(deB.respuesta().id()).isNotEqualTo(deA.respuesta().id());
		assertThat(abierta(deA.respuesta().id())).isTrue();
	}

	@Test
	void altaDentroDeTransaccionExternaRevertidaNoDejaVigenciaNiAuditoria() {
		var a = caso();
		var t = tutor(a);
		var tx = new TransactionTemplate(manager);
		var id = new UUID[1];
		tx.executeWithoutResult(s -> {
			id[0] = responsables.crear(a.admin(), a.deportista(), UUID.randomUUID(), alta(a, t, null)).respuesta().id();
			s.setRollbackOnly();
		});
		assertThat(vigencias(a, t)).isZero();
		assertThat(auditorias(id[0], "VIGENCIA_TUTOR_CREADA")).isZero();
	}

	// ---------- edición y revocación ----------

	@Test
	void edicionDeTipoDistingueNuloDeSinDeterminarYNoAuditaSinCambio() {
		var a = caso();
		var t = tutor(a);
		var creada = responsables.crear(a.admin(), a.deportista(), UUID.randomUUID(), alta(a, t, null)).respuesta();
		long v0 = Long.parseLong(creada.version());
		var sinCambio = responsables.actualizarTipo(a.admin(), a.deportista(), creada.id(), new ResponsableTipo(null, v0));
		assertThat(sinCambio.version()).isEqualTo(creada.version());
		var determinar = responsables.actualizarTipo(a.admin(), a.deportista(), creada.id(),
				new ResponsableTipo(TipoVinculoTutor.SIN_DETERMINAR, v0));
		assertThat(determinar.tipo()).isEqualTo(TipoVinculoTutor.SIN_DETERMINAR);
		assertThat(determinar.version()).isEqualTo(Long.toString(v0 + 1));
		assertThat(auditorias(creada.id(), "VIGENCIA_TUTOR_TIPO_ACTUALIZADA")).isEqualTo(1);
		assertThatThrownBy(() -> responsables.actualizarTipo(a.admin(), a.deportista(), creada.id(),
				new ResponsableTipo(TipoVinculoTutor.MADRE, v0))).isInstanceOfSatisfying(ExcepcionNegocio.class,
						e -> assertThat(e.getCodigo()).isEqualTo("EDICION_DESACTUALIZADA"));
		var aNulo = responsables.actualizarTipo(a.admin(), a.deportista(), creada.id(), new ResponsableTipo(null, v0 + 1));
		assertThat(aNulo.tipo()).isNull();
		assertThat(auditorias(creada.id(), "VIGENCIA_TUTOR_TIPO_ACTUALIZADA")).isEqualTo(2);
	}

	@Test
	void revocacionCierraSinReabrirseConReactivacionesNiAuditarDeMas() {
		var a = caso();
		var t = tutor(a);
		var creada = responsables.crear(a.admin(), a.deportista(), UUID.randomUUID(), alta(a, t, null)).respuesta();
		var cerrada = responsables.revocar(a.admin(), a.deportista(), creada.id(),
				new ResponsableRevision(Long.valueOf(creada.version())));
		assertThat(cerrada.cerradoEn()).isNotNull();
		assertThat(cerrada.causaCierre().name()).isEqualTo("REVOCACION_ADMIN");
		assertThat(auditorias(creada.id(), "VIGENCIA_TUTOR_CERRADA")).isEqualTo(1);
		long v1 = Long.parseLong(cerrada.version());
		assertThatThrownBy(() -> responsables.revocar(a.admin(), a.deportista(), creada.id(), new ResponsableRevision(v1)))
				.isInstanceOfSatisfying(ExcepcionNegocio.class,
						e -> assertThat(e.getCodigo()).isEqualTo("ESTADO_INCOMPATIBLE"));
		assertThatThrownBy(() -> responsables.actualizarTipo(a.admin(), a.deportista(), creada.id(),
				new ResponsableTipo(TipoVinculoTutor.MADRE, v1))).isInstanceOfSatisfying(ExcepcionNegocio.class,
						e -> assertThat(e.getCodigo()).isEqualTo("ESTADO_INCOMPATIBLE"));
		long versionTutor = versionTutor(t);
		tutores.desactivar(a.admin(), t, versionTutor, DATOS);
		tutores.activar(a.admin(), t, versionTutor + 1, DATOS);
		assertThat(abierta(creada.id())).isFalse();
		assertThat(auditorias(creada.id(), "VIGENCIA_TUTOR_CERRADA")).isEqualTo(1);
	}

	// ---------- carreras con las cuatro bajas de C2-A ----------

	private enum Baja {
		TUTOR, FAMILIA, DEPORTISTA, VINCULO
	}

	private enum Nivel {
		READ_COMMITTED(TransactionDefinition.ISOLATION_READ_COMMITTED),
		REPEATABLE_READ(TransactionDefinition.ISOLATION_REPEATABLE_READ);

		final int valor;

		Nivel(int valor) {
			this.valor = valor;
		}
	}

	private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> matriz() {
		var filas = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
		for (Baja b : Baja.values()) {
			for (Nivel n : Nivel.values()) {
				filas.add(org.junit.jupiter.params.provider.Arguments.of(b, n));
			}
		}
		return filas.stream();
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.MethodSource("matriz")
	void altaPrimeroLaBajaEsperaYNoDejaVigenciasAbiertasBajoPadresInactivos(Baja baja, Nivel nivelDeLaBaja)
			throws Exception {
		var a = caso();
		var t = tutor(a);
		var altaHecha = new CountDownLatch(1);
		var liberar = new CountDownLatch(1);
		var alta = HILOS.submit(() -> tx(Nivel.READ_COMMITTED).execute(s -> {
			var r = responsables.crear(a.admin(), a.deportista(), UUID.randomUUID(), alta(a, t, null));
			altaHecha.countDown();
			esperar(liberar);
			return r;
		}));
		assertThat(altaHecha.await(10, TimeUnit.SECONDS)).isTrue();
		var cierre = HILOS.submit(() -> tx(nivelDeLaBaja).execute(s -> {
			baja(baja, a, t);
			return null;
		}));
		Thread.sleep(500);
		assertThat(cierre.isDone()).as("la baja debe esperar el alta sin confirmar").isFalse();
		liberar.countDown();
		alta.get(10, TimeUnit.SECONDS);
		try {
			cierre.get(15, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			// Con una instantánea que no ve el alta confirmada la FK de V14 impide la baja: se repite el comando.
			assertThat(nivelDeLaBaja).isEqualTo(Nivel.REPEATABLE_READ);
			assertThat(e.getCause()).isInstanceOf(PessimisticLockingFailureException.class);
			baja(baja, a, t);
		}
		assertThat(abiertasBajoPadresInactivos(a)).isZero();
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.MethodSource("matriz")
	void bajaPrimeroElAltaEsperaYTerminaEnConflictoNuncaEnVigenciaAbierta(Baja baja, Nivel nivelDelAlta)
			throws Exception {
		var a = caso();
		var t = tutor(a);
		var bajaHecha = new CountDownLatch(1);
		var liberar = new CountDownLatch(1);
		var cierre = HILOS.submit(() -> tx(Nivel.READ_COMMITTED).execute(s -> {
			baja(baja, a, t);
			bajaHecha.countDown();
			esperar(liberar);
			return null;
		}));
		assertThat(bajaHecha.await(10, TimeUnit.SECONDS)).isTrue();
		var pedido = alta(a, t, null);
		var intento = HILOS.submit(() -> tx(nivelDelAlta).execute(
				s -> responsables.crear(a.admin(), a.deportista(), UUID.randomUUID(), pedido)));
		Thread.sleep(500);
		liberar.countDown();
		cierre.get(10, TimeUnit.SECONDS);
		assertThatThrownBy(() -> intento.get(15, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
				.satisfies(e -> assertThat(e.getCause()).isInstanceOfAny(ExcepcionNegocio.class,
						PessimisticLockingFailureException.class,
						org.springframework.orm.ObjectOptimisticLockingFailureException.class,
						jakarta.persistence.OptimisticLockException.class));
		assertThat(vigencias(a, t)).isZero();
		assertThat(abiertasBajoPadresInactivos(a)).isZero();
	}

	// ---------- soporte ----------

	private enum Revision {
		FAMILIA("versionFamilia"), DEPORTISTA("versionDeportista"), TUTOR("versionTutor"), VINCULOS("versionVinculos");

		final String campo;

		Revision(String campo) {
			this.campo = campo;
		}

		ResponsableAlta obsoleta(ResponsableAlta p) {
			return new ResponsableAlta(p.tutorId(), p.tipo(), this == FAMILIA ? p.versionFamilia() + 1 : p.versionFamilia(),
					this == DEPORTISTA ? p.versionDeportista() + 1 : p.versionDeportista(),
					this == TUTOR ? p.versionTutor() + 1 : p.versionTutor(),
					this == VINCULOS ? p.versionVinculos() + 1 : p.versionVinculos());
		}
	}

	private record Caso(UsuarioAutenticado admin, UUID familia, UUID deportista) {}

	private Caso caso() {
		var datos = new DatosDb(jdbc);
		var escuela = datos.escuela("resp-java-" + UUID.randomUUID());
		var admin = new UsuarioAutenticado(datos.admin(escuela, "admin@example.invalid", true), escuela, null, Rol.ADMIN);
		var familia = datos.familia(escuela, "Familia", true);
		var deportista = datos.deportista(escuela, "12345678", "Nombre", "Apellido");
		datos.vinculo(escuela, familia, deportista, "ACTIVO", true, admin.id());
		return new Caso(admin, familia, deportista);
	}

	private UUID tutor(Caso c) {
		return new DatosDb(jdbc).tutor(c.admin().escuelaId(), c.familia(), "Nombre", "Tutor");
	}

	/** Pedido con las revisiones vigentes leídas de la base. */
	private ResponsableAlta alta(Caso c, UUID tutor, TipoVinculoTutor tipo) {
		long familia = jdbc.sql("SELECT version FROM gestion_patin.familia WHERE id=:id").param("id", c.familia())
				.query(Long.class).single();
		long deportista = jdbc.sql("SELECT version FROM gestion_patin.deportista WHERE id=:id")
				.param("id", c.deportista()).query(Long.class).single();
		return new ResponsableAlta(tutor, tipo, familia, deportista, versionTutor(tutor), revisionVinculos(c));
	}

	private long versionTutor(UUID tutor) {
		return jdbc.sql("SELECT version FROM gestion_patin.tutor WHERE id=:id").param("id", tutor).query(Long.class).single();
	}

	private long revisionVinculos(Caso c) {
		return jdbc.sql("SELECT version_vinculos FROM gestion_patin.deportista WHERE id=:id").param("id", c.deportista())
				.query(Long.class).single();
	}

	private void baja(Baja baja, Caso a, UUID tutor) {
		var admin = a.admin();
		switch (baja) {
			case TUTOR -> tutores.desactivar(admin, tutor, versionTutor(tutor), DATOS);
			case FAMILIA -> familias.desactivar(admin, a.familia(), jdbc.sql("SELECT version FROM gestion_patin.familia WHERE id=:id")
					.param("id", a.familia()).query(Long.class).single(), DATOS);
			case DEPORTISTA -> deportistas.desactivar(admin, a.deportista(),
					jdbc.sql("SELECT version FROM gestion_patin.deportista WHERE id=:id").param("id", a.deportista())
							.query(Long.class).single(), DATOS);
			case VINCULO -> vinculos.revocar(admin, a.familia(), a.deportista(), revisionVinculos(a), DATOS);
		}
	}

	private TransactionTemplate tx(Nivel nivel) {
		var t = new TransactionTemplate(manager);
		t.setIsolationLevel(nivel.valor);
		return t;
	}

	private static void esperar(CountDownLatch latch) {
		try {
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private int vigencias(Caso c, UUID tutor) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.tutor_deportista WHERE escuela_id=:e AND tutor_id=:t")
				.param("e", c.admin().escuelaId()).param("t", tutor).query(Integer.class).single();
	}

	private boolean abierta(UUID vigencia) {
		return jdbc.sql("SELECT cerrado_en IS NULL FROM gestion_patin.tutor_deportista WHERE id=:id").param("id", vigencia)
				.query(Boolean.class).single();
	}

	private int auditorias(UUID recurso, String accion) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE recurso_id=:r AND accion=:a")
				.param("r", recurso).param("a", accion).query(Integer.class).single();
	}

	/** Invariante de C2-A: una vigencia abierta exige familia, deportista, tutor y vínculo activos. */
	private int abiertasBajoPadresInactivos(Caso c) {
		return jdbc.sql("""
				SELECT count(*) FROM gestion_patin.tutor_deportista td
				JOIN gestion_patin.familia f ON f.id = td.familia_id
				JOIN gestion_patin.deportista d ON d.id = td.deportista_id
				JOIN gestion_patin.tutor t ON t.id = td.tutor_id
				JOIN gestion_patin.familia_deportista fd ON fd.familia_id = td.familia_id AND fd.deportista_id = td.deportista_id
				WHERE td.escuela_id = :e AND td.cerrado_en IS NULL
				  AND (NOT f.activa OR NOT d.activo OR NOT t.activo OR fd.estado <> 'ACTIVO')
				""").param("e", c.admin().escuelaId()).query(Integer.class).single();
	}
}
