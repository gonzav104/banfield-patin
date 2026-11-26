package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.portal.FamiliaPortalResponsablesService;
import com.banfieldpatin.backend.familias.portal.dto.ResponsableDeFamilia;
import com.banfieldpatin.backend.familias.responsables.TipoVinculoTutor;
import com.banfieldpatin.backend.familias.tutores.TutorAdminService;
import com.banfieldpatin.backend.familias.vinculos.VinculoAdminService;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/**
 * Los responsables que ve la FAMILIA contra PostgreSQL real: el alcance es el WHERE de la consulta, así que dos familias que
 * comparten un deportista nunca se ven entre sí, otra escuela no se cruza, y la revocación del vínculo, la baja del tutor o la
 * revocación individual retiran al responsable. Una lista vacía (deportista propio sin responsables) es distinta del 404.
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
class FamiliaPortalResponsablesDbTest extends BaseDbTest {
	private static final DatosSolicitud DATOS = new DatosSolicitud(null, "JUnit");

	@Autowired JdbcClient jdbc;
	@Autowired FamiliaPortalResponsablesService servicio;
	@Autowired VinculoAdminService vinculos;
	@Autowired TutorAdminService tutores;
	@Autowired FamiliaAdminService familias;

	private record Escenario(UUID escuela, UsuarioAutenticado admin, UUID deportista, UUID familiaA, UUID familiaB,
			UUID tutorA, UUID tutorB, UUID vigenciaA, UUID vigenciaB) {
		UsuarioAutenticado deA() {
			return new UsuarioAutenticado(UUID.randomUUID(), escuela, familiaA, Rol.FAMILIA);
		}

		UsuarioAutenticado deB() {
			return new UsuarioAutenticado(UUID.randomUUID(), escuela, familiaB, Rol.FAMILIA);
		}
	}

	/** Un deportista vinculado a dos familias, cada una con un tutor responsable de tipo distinto. */
	private Escenario escenario() {
		var datos = new DatosDb(jdbc);
		var escuela = datos.escuela("resp-portal-" + UUID.randomUUID());
		var admin = new UsuarioAutenticado(datos.admin(escuela, "admin@example.invalid", true), escuela, null, Rol.ADMIN);
		var familiaA = datos.familia(escuela, "Familia A", true);
		var familiaB = datos.familia(escuela, "Familia B", true);
		var deportista = datos.deportista(escuela, "12345678", "Nombre", "Apellido");
		datos.vinculo(escuela, familiaA, deportista, "ACTIVO", true, admin.id());
		datos.vinculo(escuela, familiaB, deportista, "ACTIVO", false, admin.id());
		var tutorA = datos.tutor(escuela, familiaA, "Ana", "Alvarez");
		var tutorB = datos.tutor(escuela, familiaB, "Beto", "Benitez");
		return new Escenario(escuela, admin, deportista, familiaA, familiaB, tutorA, tutorB,
				vigencia(escuela, admin, familiaA, deportista, tutorA, "MADRE"),
				vigencia(escuela, admin, familiaB, deportista, tutorB, "PADRE"));
	}

	private UUID vigencia(UUID escuela, UsuarioAutenticado admin, UUID familia, UUID deportista, UUID tutor, String tipo) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.tutor_deportista
				 (escuela_id,familia_id,deportista_id,tutor_id,tipo,creado_por,solicitud_id,huella_solicitud)
				VALUES (:e,:f,:d,:t,:tipo,:a,gen_random_uuid(),repeat('a',64)) RETURNING id
				""").param("e", escuela).param("f", familia).param("d", deportista).param("t", tutor)
				.param("tipo", tipo).param("a", admin.id()).query(UUID.class).single();
	}

	private void assert404(UsuarioAutenticado usuario, UUID deportista) {
		assertThatThrownBy(() -> servicio.responsables(usuario, deportista)).isInstanceOfSatisfying(ExcepcionNegocio.class,
				e -> {
					assertThat(e.getEstado().value()).isEqualTo(404);
					assertThat(e.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO");
				});
	}

	@Test
	void dosFamiliasQueCompartenUnDeportistaSoloVenASusPropiosResponsables() {
		var e = escenario();
		assertThat(servicio.responsables(e.deA(), e.deportista()).responsables())
				.containsExactly(new ResponsableDeFamilia(e.tutorA(), "Ana", "Alvarez", TipoVinculoTutor.MADRE));
		assertThat(servicio.responsables(e.deB(), e.deportista()).responsables())
				.containsExactly(new ResponsableDeFamilia(e.tutorB(), "Beto", "Benitez", TipoVinculoTutor.PADRE));
	}

	@Test
	void unaFamiliaNoInfiereLaCantidadNiLaIdentidadDeLosResponsablesAjenos() {
		var e = escenario();
		var datos = new DatosDb(jdbc);
		var otroDeB = datos.tutor(e.escuela(), e.familiaB(), "Carla", "Campos");
		vigencia(e.escuela(), e.admin(), e.familiaB(), e.deportista(), otroDeB, "OTRO_RESPONSABLE");
		// B tiene dos responsables; A sigue viendo exactamente uno, el suyo, y nada de B aparece en su respuesta.
		var deA = servicio.responsables(e.deA(), e.deportista()).responsables();
		assertThat(deA).hasSize(1).extracting(ResponsableDeFamilia::tutorId).containsExactly(e.tutorA());
		assertThat(servicio.responsables(e.deB(), e.deportista()).responsables()).hasSize(2);
	}

	@Test
	void elTipoNuloYSinDeterminarSonValoresDistintos() {
		var e = escenario();
		var datos = new DatosDb(jdbc);
		var sinTipo = datos.tutor(e.escuela(), e.familiaA(), "Dora", "Duarte");
		var sinDeterminar = datos.tutor(e.escuela(), e.familiaA(), "Eva", "Estevez");
		jdbc.sql("""
				INSERT INTO gestion_patin.tutor_deportista
				 (escuela_id,familia_id,deportista_id,tutor_id,tipo,creado_por,solicitud_id,huella_solicitud)
				VALUES (:e,:f,:d,:t,NULL,:a,gen_random_uuid(),repeat('b',64))
				""").param("e", e.escuela()).param("f", e.familiaA()).param("d", e.deportista()).param("t", sinTipo)
				.param("a", e.admin().id()).update();
		vigencia(e.escuela(), e.admin(), e.familiaA(), e.deportista(), sinDeterminar, "SIN_DETERMINAR");

		var porNombre = servicio.responsables(e.deA(), e.deportista()).responsables().stream()
				.collect(java.util.stream.Collectors.toMap(ResponsableDeFamilia::nombre,
						r -> java.util.Optional.ofNullable(r.tipo()).map(Enum::name).orElse("NULO")));
		assertThat(porNombre).containsEntry("Dora", "NULO").containsEntry("Eva", "SIN_DETERMINAR")
				.containsEntry("Ana", "MADRE");
	}

	@Test
	void laListaVaciaDeUnDeportistaPropioEsDistintaDelNoEncontrado() {
		var e = escenario();
		var datos = new DatosDb(jdbc);
		var familiaC = datos.familia(e.escuela(), "Familia C", true);
		var sinResponsables = datos.deportista(e.escuela(), "87654321", "Sin", "Resp");
		datos.vinculo(e.escuela(), familiaC, sinResponsables, "ACTIVO", true, e.admin().id());
		var usuarioC = new UsuarioAutenticado(UUID.randomUUID(), e.escuela(), familiaC, Rol.FAMILIA);
		assertThat(servicio.responsables(usuarioC, sinResponsables).responsables()).isEmpty();
		// El deportista de A/B no es de C: 404, no lista vacía.
		assert404(usuarioC, e.deportista());
	}

	@Test
	void otraEscuelaUnaCuentaSinFamiliaYUnDeportistaInexistenteDanElMismo404() {
		var e = escenario();
		var otra = escenario();
		assert404(otra.deA(), e.deportista());
		assert404(new UsuarioAutenticado(UUID.randomUUID(), e.escuela(), null, Rol.FAMILIA), e.deportista());
		assert404(e.deA(), UUID.randomUUID());
		// Misma familia id pero escuela distinta tampoco cruza.
		assert404(new UsuarioAutenticado(UUID.randomUUID(), otra.escuela(), e.familiaA(), Rol.FAMILIA), e.deportista());
	}

	@Test
	void laRevocacionDelVinculoFamiliarRetiraElAccesoYLosResponsablesDeEsaFamilia() {
		var e = escenario();
		long revision = jdbc.sql("SELECT version_vinculos FROM gestion_patin.deportista WHERE id=:id")
				.param("id", e.deportista()).query(Long.class).single();
		vinculos.revocar(e.admin(), e.familiaA(), e.deportista(), revision, DATOS);
		assert404(e.deA(), e.deportista());
		// La otra familia sigue viendo solo lo suyo y no se entera del cambio.
		assertThat(servicio.responsables(e.deB(), e.deportista()).responsables()).hasSize(1);
	}

	@Test
	void unaFamiliaInactivaNoLeeResponsables() {
		var e = escenario();
		long version = jdbc.sql("SELECT version FROM gestion_patin.familia WHERE id=:id").param("id", e.familiaA())
				.query(Long.class).single();
		familias.desactivar(e.admin(), e.familiaA(), version, DATOS);
		assert404(e.deA(), e.deportista());
	}

	@Test
	void unaVigenciaCerradaNoApareceNiAlReactivarseElTutorNiCambiandoLaSesion() {
		var e = escenario();
		long version = jdbc.sql("SELECT version FROM gestion_patin.tutor WHERE id=:id").param("id", e.tutorA())
				.query(Long.class).single();
		tutores.desactivar(e.admin(), e.tutorA(), version, DATOS);
		assertThat(servicio.responsables(e.deA(), e.deportista()).responsables()).isEmpty();
		tutores.activar(e.admin(), e.tutorA(), version + 1, DATOS);
		// La reactivación no reabre el historial, y otra sesión de la misma familia tampoco lo ve.
		assertThat(servicio.responsables(e.deA(), e.deportista()).responsables()).isEmpty();
		assertThat(servicio.responsables(new UsuarioAutenticado(UUID.randomUUID(), e.escuela(), e.familiaA(), Rol.FAMILIA),
				e.deportista()).responsables()).isEmpty();
		assertThat(servicio.responsables(e.deB(), e.deportista()).responsables()).hasSize(1);
	}

	@Test
	void laRevocacionIndividualRetiraAlResponsableSinTocarLaOtraFamilia() {
		var e = escenario();
		jdbc.sql("""
				UPDATE gestion_patin.tutor_deportista SET cerrado_en = clock_timestamp(), cerrado_por = :a,
				 causa_cierre = 'REVOCACION_ADMIN', version = version + 1 WHERE id = :id
				""").param("a", e.admin().id()).param("id", e.vigenciaA()).update();
		assertThat(servicio.responsables(e.deA(), e.deportista()).responsables()).isEmpty();
		assertThat(servicio.responsables(e.deB(), e.deportista()).responsables()).hasSize(1);
	}

	@Test
	void elOrdenEsPorApellidoYNombreSinDependerDelAlta() {
		var e = escenario();
		var datos = new DatosDb(jdbc);
		for (String[] n : new String[][] { { "Zoe", "Zapata" }, { "Abel", "Zapata" }, { "Mia", "Acosta" } }) {
			vigencia(e.escuela(), e.admin(), e.familiaA(), e.deportista(),
					datos.tutor(e.escuela(), e.familiaA(), n[0], n[1]), "OTRO_RESPONSABLE");
		}
		assertThat(servicio.responsables(e.deA(), e.deportista()).responsables())
				.extracting(r -> r.apellido() + " " + r.nombre())
				.containsExactly("Acosta Mia", "Alvarez Ana", "Zapata Abel", "Zapata Zoe");
	}
}
