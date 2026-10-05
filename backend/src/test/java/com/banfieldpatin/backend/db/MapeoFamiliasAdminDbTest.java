package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.dto.FamiliaDetalle;
import com.banfieldpatin.backend.familias.dto.FamiliaSolicitud;
import com.banfieldpatin.backend.familias.invitaciones.GeneradorTokenInvitacion;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/**
 * Alta, edicion y cambio de estado de familias contra PostgreSQL real (beans y proxies transaccionales reales,
 * Flyway V1-V4, ddl-auto=validate): mapeo de la entidad, auditoria solo ante cambios reales, ausencia de cascada
 * al desactivar y aislamiento por escuela.
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class MapeoFamiliasAdminDbTest extends BaseDbTest {

	private static final DatosSolicitud DATOS = new DatosSolicitud("10.0.0.9", "JUnit");

	@Autowired
	JdbcClient jdbc;
	@Autowired
	FamiliaAdminService servicio;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UUID adminId;
	private UsuarioAutenticado admin;
	private UsuarioAutenticado adminDeB;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("fmap-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("fmap-b-" + UUID.randomUUID());
		adminId = datos.admin(escuelaA, "admin@a.example", true);
		admin = new UsuarioAutenticado(adminId, escuelaA, null, Rol.ADMIN);
		adminDeB = new UsuarioAutenticado(datos.admin(escuelaB, "admin@b.example", true), escuelaB, null, Rol.ADMIN);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private long auditorias(UUID escuela, String accion) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = :a")
				.param("e", escuela).param("a", accion).query(Long.class).single();
	}

	private long auditoriasTotales(UUID escuela) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e").param("e", escuela)
				.query(Long.class).single();
	}

	private Map<String, Object> fila(UUID id) {
		return jdbc.sql("SELECT escuela_id, nombre_referencia, activa FROM gestion_patin.familia WHERE id = :id")
				.param("id", id).query().singleRow();
	}

	@Test
	void crearPersisteTrimeadaActivaEnLaEscuelaDelTokenYAuditaOrigenAdmin() {
		FamiliaDetalle creada = servicio.crear(admin, new FamiliaSolicitud("  Perez  "), DATOS);

		Map<String, Object> fila = fila(creada.id());
		assertThat(fila.get("escuela_id")).isEqualTo(escuelaA);
		assertThat(fila.get("nombre_referencia")).isEqualTo("Perez");
		assertThat(fila.get("activa")).isEqualTo(true);
		assertThat(creada.tutores()).isEmpty();
		Map<String, Object> evento = jdbc.sql("""
				SELECT usuario_id, recurso_tipo, recurso_id, detalle::text AS detalle
				FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'FAMILIA_CREADA'
				""").param("e", escuelaA).query().singleRow();
		assertThat(evento.get("usuario_id")).isEqualTo(adminId);
		assertThat(evento.get("recurso_tipo")).isEqualTo("FAMILIA");
		assertThat(evento.get("recurso_id")).isEqualTo(creada.id());
		assertThat((String) evento.get("detalle")).contains("\"origen\"").contains("ADMIN");
	}

	@Test
	void dosFamiliasConElMismoNombreSePermiten() {
		servicio.crear(admin, new FamiliaSolicitud("Gomez"), DATOS);
		servicio.crear(admin, new FamiliaSolicitud("Gomez"), DATOS);

		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.familia WHERE escuela_id = :e")
				.param("e", escuelaA).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void actualizarAuditaSoloCuandoElNombreCambiaYSinValores() {
		UUID id = servicio.crear(admin, new FamiliaSolicitud("Perez"), DATOS).id();

		servicio.actualizar(admin, id, new FamiliaSolicitud("Perez"), DATOS);
		assertThat(auditorias(escuelaA, "FAMILIA_ACTUALIZADA")).isZero();

		FamiliaDetalle cambiada = servicio.actualizar(admin, id, new FamiliaSolicitud("Gomez"), DATOS);

		assertThat(cambiada.nombreReferencia()).isEqualTo("Gomez");
		assertThat(fila(id).get("nombre_referencia")).isEqualTo("Gomez");
		String detalle = jdbc.sql("SELECT detalle::text FROM gestion_patin.auditoria "
				+ "WHERE escuela_id = :e AND accion = 'FAMILIA_ACTUALIZADA'").param("e", escuelaA)
				.query(String.class).single();
		assertThat(detalle).contains("camposModificados").contains("nombreReferencia")
				.doesNotContain("Gomez").doesNotContain("Perez");
	}

	@Test
	void actualizarUnaFamiliaInactivaEstaPermitidoYNoCambiaSuEstado() {
		UUID id = datos.familia(escuelaA, "Vieja", false);

		FamiliaDetalle detalle = servicio.actualizar(admin, id, new FamiliaSolicitud("Nueva"), DATOS);

		assertThat(detalle.activa()).isFalse();
		assertThat(fila(id).get("activa")).isEqualTo(false);
		assertThat(fila(id).get("nombre_referencia")).isEqualTo("Nueva");
	}

	@Test
	void desactivarYActivarAuditanSoloElCambioRealYSonIdempotentes() {
		UUID id = datos.familia(escuelaA, "Perez", true);

		assertThat(servicio.activar(admin, id, DATOS).activa()).isTrue();
		assertThat(auditoriasTotales(escuelaA)).isZero();

		assertThat(servicio.desactivar(admin, id, DATOS).activa()).isFalse();
		assertThat(servicio.desactivar(admin, id, DATOS).activa()).isFalse();
		assertThat(auditorias(escuelaA, "FAMILIA_DESACTIVADA")).isEqualTo(1);

		assertThat(servicio.activar(admin, id, DATOS).activa()).isTrue();
		assertThat(servicio.activar(admin, id, DATOS).activa()).isTrue();
		assertThat(auditorias(escuelaA, "FAMILIA_ACTIVADA")).isEqualTo(1);
		assertThat(fila(id).get("activa")).isEqualTo(true);
	}

	@Test
	void desactivarNoTieneCascadaTutoresVinculosUsuariosEInvitacionesQuedanIntactos() {
		UUID familia = datos.familia(escuelaA, "Con Todo", true);
		UUID tutor1 = datos.tutor(escuelaA, familia, "Ana", "Con Todo");
		UUID tutor2 = datos.tutor(escuelaA, familia, "Luis", "Con Todo");
		UUID dep1 = datos.deportista(escuelaA, "30111222", "Nico", "Con Todo");
		UUID dep2 = datos.deportista(escuelaA, "30111223", "Sofi", "Con Todo");
		UUID vinculo1 = datos.vinculo(escuelaA, familia, dep1, "ACTIVO", true, adminId);
		UUID vinculo2 = datos.vinculo(escuelaA, familia, dep2, "ACTIVO", false, adminId);
		UUID usuario = datos.usuarioFamilia(escuelaA, familia, "madre@a.example");
		UUID invitacion = datos.invitacionPendiente(escuelaA, familia, adminId, GeneradorTokenInvitacion.sha256Hex(UUID.randomUUID().toString()));
		String antes = estadoDependiente(escuelaA);

		servicio.desactivar(admin, familia, DATOS);

		assertThat(fila(familia).get("activa")).isEqualTo(false);
		assertThat(estadoDependiente(escuelaA)).isEqualTo(antes);
		assertThat(antes).contains(tutor1.toString(), tutor2.toString(), vinculo1.toString(), vinculo2.toString(),
				dep1.toString(), dep2.toString(), usuario.toString(), invitacion.toString());
		// Los vinculos siguen ACTIVO y los deportistas activos.
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.familia_deportista WHERE familia_id = :f AND estado = 'ACTIVO'")
				.param("f", familia).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.deportista WHERE escuela_id = :e AND activo")
				.param("e", escuelaA).query(Long.class).single()).isEqualTo(2);
	}

	/** Foto textual de todo lo colgado de la familia (ids y estados) para comparar antes y despues. */
	private String estadoDependiente(UUID escuela) {
		List<String> partes = List.of(
				jdbc.sql("SELECT string_agg(id || ':' || activo, ',' ORDER BY id) FROM gestion_patin.tutor WHERE escuela_id = :e")
						.param("e", escuela).query(String.class).single(),
				jdbc.sql("SELECT string_agg(id || ':' || estado || ':' || es_principal, ',' ORDER BY id) FROM gestion_patin.familia_deportista WHERE escuela_id = :e")
						.param("e", escuela).query(String.class).single(),
				jdbc.sql("SELECT string_agg(id || ':' || activo, ',' ORDER BY id) FROM gestion_patin.deportista WHERE escuela_id = :e")
						.param("e", escuela).query(String.class).single(),
				jdbc.sql("SELECT string_agg(id || ':' || activo, ',' ORDER BY id) FROM gestion_patin.usuario WHERE escuela_id = :e AND rol = 'FAMILIA'")
						.param("e", escuela).query(String.class).single(),
				jdbc.sql("SELECT string_agg(id || ':' || (usado_en IS NULL) || ':' || (revocada_en IS NULL), ',' ORDER BY id) FROM gestion_patin.invitacion WHERE escuela_id = :e")
						.param("e", escuela).query(String.class).single());
		return String.join("|", partes);
	}

	@Test
	void unAdminDeOtraEscuelaNoVeNiCambiaNada() {
		UUID ajena = datos.familia(escuelaB, "Ajena", true);

		assertThatThrownBy(() -> servicio.obtener(admin, ajena)).isInstanceOfSatisfying(ExcepcionNegocio.class,
				e -> assertThat(e.getCodigo()).isEqualTo("FAMILIA_NO_ENCONTRADA"));
		assertThatThrownBy(() -> servicio.actualizar(admin, ajena, new FamiliaSolicitud("Robada"), DATOS))
				.isInstanceOf(ExcepcionNegocio.class);
		assertThatThrownBy(() -> servicio.desactivar(admin, ajena, DATOS)).isInstanceOf(ExcepcionNegocio.class);
		assertThatThrownBy(() -> servicio.activar(admin, UUID.randomUUID(), DATOS))
				.isInstanceOf(ExcepcionNegocio.class);

		assertThat(fila(ajena).get("nombre_referencia")).isEqualTo("Ajena");
		assertThat(fila(ajena).get("activa")).isEqualTo(true);
		assertThat(auditoriasTotales(escuelaA)).isZero();
		assertThat(auditoriasTotales(escuelaB)).isZero();
		// Y el admin de la escuela dueña si la ve.
		assertThat(servicio.obtener(adminDeB, ajena).nombreReferencia()).isEqualTo("Ajena");
	}

	@Test
	void unNombreDeMasDe150CaracteresNoLlegaALaBase() {
		// La validacion del DTO lo rechaza antes (400); aqui se comprueba el limite real de la columna.
		assertThatThrownBy(() -> servicio.crear(admin, new FamiliaSolicitud("a".repeat(151)), DATOS))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThat(auditoriasTotales(escuelaA)).isZero();
	}
}
