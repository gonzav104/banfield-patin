package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.portal.FamiliaPortalService;
import com.banfieldpatin.backend.familias.portal.dto.DeportistaDeFamilia;
import com.banfieldpatin.backend.familias.portal.dto.DeportistaDeFamiliaDetalle;
import com.banfieldpatin.backend.familias.portal.dto.MiFamiliaRespuesta;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import tools.jackson.databind.json.JsonMapper;

/**
 * Matriz IDOR del portal de FAMILIA contra PostgreSQL real (REQ-POR-07): 2 escuelas x 2 familias, vinculos en cada estado,
 * un deportista ACTIVO en las DOS familias de una escuela, deportistas inactivos con vinculo ACTIVO (visibles con
 * {@code activo=false}) y con vinculo REVOCADO (ocultos). La autorizacion es el WHERE de UNA sentencia: cualquier id que no
 * sea propio (otra familia, otra escuela, sin vinculo activo, aleatorio) da el MISMO 404 y ninguna respuesta contiene un
 * nombre, DNI o id ajeno. Una familia inactiva se prueba solo a nivel de repositorio (por HTTP la corta antes la
 * revalidacion central de la sesion: FamiliaPortalHttpDbTest).
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class FamiliaPortalAislamientoDbTest extends BaseVinculosDb {

	@Autowired
	FamiliaPortalService portal;
	@Autowired
	FamiliaRepository familias;
	@Autowired
	JsonMapper json;

	private UUID fa1;
	private UUID fa2;
	private UUID fb1;
	private UUID fb2;
	// escuela A
	private UUID dSoloFa1;
	private UUID dAmbas;
	private UUID dRevocado;
	private UUID dPendiente;
	private UUID dRechazado;
	private UUID dInactivoActivo;
	private UUID dInactivoRevocado;
	private UUID dSoloFa2;
	private UUID dSinVinculo;
	// escuela B
	private UUID dB1;
	private UUID dB2;
	private UsuarioAutenticado usuarioFa1;
	private UsuarioAutenticado usuarioFa2;
	private UsuarioAutenticado usuarioFb1;
	private UsuarioAutenticado usuarioFb2;

	private UUID dep(UUID escuela, String dni, String nombre, String apellido, boolean activo) {
		return datos.deportista(escuela, dni, nombre, apellido, activo);
	}

	private UsuarioAutenticado familia(UUID escuela, UUID familiaId) {
		return new UsuarioAutenticado(UUID.randomUUID(), escuela, familiaId, Rol.FAMILIA);
	}

	@BeforeEach
	void sembrarMatriz() {
		fa1 = datos.familia(escuelaA, "Familia A1", true);
		fa2 = datos.familia(escuelaA, "Familia A2", true);
		fb1 = datos.familia(escuelaB, "Familia B1", true);
		fb2 = datos.familia(escuelaB, "Familia B2", true);

		dSoloFa1 = dep(escuelaA, "30000001", "Bruno", "Alvarez", true);
		dAmbas = dep(escuelaA, "30000002", "Carla", "alvarez", true);
		dRevocado = dep(escuelaA, "30000003", "Dario", "Revocado", true);
		dPendiente = dep(escuelaA, "30000004", "Elena", "Pendiente", true);
		dRechazado = dep(escuelaA, "30000005", "Fede", "Rechazado", true);
		dInactivoActivo = dep(escuelaA, "30000006", "Gala", "Zeta", false);
		dInactivoRevocado = dep(escuelaA, "30000007", "Hugo", "Inactivorevocado", false);
		dSoloFa2 = dep(escuelaA, "30000008", "Ivan", "Soloafa2", true);
		dSinVinculo = dep(escuelaA, "30000009", "Julia", "Sinvinculo", true);
		dB1 = dep(escuelaB, "30000001", "Karen", "Debe", true);
		dB2 = dep(escuelaB, "30000002", "Luis", "Debdos", true);

		datos.vinculo(escuelaA, fa1, dSoloFa1, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa1, dAmbas, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa2, dAmbas, "ACTIVO", false, adminId);
		datos.vinculo(escuelaA, fa1, dRevocado, "REVOCADO", false, adminId);
		datos.vinculo(escuelaA, fa1, dPendiente, "PENDIENTE", false, null);
		datos.vinculo(escuelaA, fa1, dRechazado, "RECHAZADO", false, null);
		datos.vinculo(escuelaA, fa1, dInactivoActivo, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa1, dInactivoRevocado, "REVOCADO", false, adminId);
		datos.vinculo(escuelaA, fa2, dSoloFa2, "ACTIVO", true, adminId);
		datos.vinculo(escuelaB, fb1, dB1, "ACTIVO", true, adminDeB.id());
		datos.vinculo(escuelaB, fb2, dB2, "ACTIVO", true, adminDeB.id());

		usuarioFa1 = familia(escuelaA, fa1);
		usuarioFa2 = familia(escuelaA, fa2);
		usuarioFb1 = familia(escuelaB, fb1);
		usuarioFb2 = familia(escuelaB, fb2);
	}

	private List<DeportistaDeFamilia> lista(UsuarioAutenticado u) {
		return portal.deportistas(u, Pagina.pedir(0, 100)).contenido();
	}

	private static void assert404Uniforme(Throwable e) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, ex -> {
			assertThat(ex.getEstado().value()).isEqualTo(404);
			assertThat(ex.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO");
			assertThat(ex.getMessage()).isEqualTo("El deportista no existe.");
			assertThat(ex.getDetalles()).isEmpty();
			assertThat(ex.getCause()).isNull();
		});
	}

	// ---------- listado exacto ----------

	@Test
	void elListadoDeA1TieneExactamenteSusTresDeportistasActivamenteVinculadosEnOrdenYElInactivoSeVeConActivoFalse() {
		List<DeportistaDeFamilia> lista = lista(usuarioFa1);

		// Orden lower(apellido), lower(nombre), id: Alvarez Bruno, alvarez Carla, Zeta Gala.
		assertThat(lista).extracting(DeportistaDeFamilia::id).containsExactly(dSoloFa1, dAmbas, dInactivoActivo);
		assertThat(lista).extracting(DeportistaDeFamilia::activo).containsExactly(true, true, false);
		assertThat(lista).extracting(DeportistaDeFamilia::apellido).containsExactly("Alvarez", "alvarez", "Zeta");
		assertThat(portal.deportistas(usuarioFa1, Pagina.pedir(0, 100)).totalElementos()).isEqualTo(3);
	}

	@Test
	void elListadoDeA2TieneSoloSuDeportistaPropioYElCompartidoConA1() {
		assertThat(lista(usuarioFa2)).extracting(DeportistaDeFamilia::id).containsExactly(dAmbas, dSoloFa2);
	}

	@Test
	void unDeportistaActivoEnLasDosFamiliasDeLaEscuelaEsVisibleParaAmbasConLosMismosDatos() {
		DeportistaDeFamiliaDetalle deA1 = portal.deportista(usuarioFa1, dAmbas);
		DeportistaDeFamiliaDetalle deA2 = portal.deportista(usuarioFa2, dAmbas);

		assertThat(deA1).isEqualTo(deA2);
		assertThat(deA1.dni()).isEqualTo("30000002");
		assertThat(deA1.activo()).isTrue();
	}

	@Test
	void lasListasDeLasFamiliasDeLaEscuelaBSonSoloLasDeB() {
		assertThat(lista(usuarioFb1)).extracting(DeportistaDeFamilia::id).containsExactly(dB1);
		assertThat(lista(usuarioFb2)).extracting(DeportistaDeFamilia::id).containsExactly(dB2);
	}

	// ---------- inactividad deportiva != revocacion ----------

	@Test
	void unDeportistaInactivoConVinculoActivoSeVeEnListadoYDetalleConActivoFalse() {
		DeportistaDeFamiliaDetalle detalle = portal.deportista(usuarioFa1, dInactivoActivo);

		assertThat(detalle.id()).isEqualTo(dInactivoActivo);
		assertThat(detalle.activo()).isFalse();
		assertThat(detalle.nombre()).isEqualTo("Gala");
		assertThat(lista(usuarioFa1)).anySatisfy(d -> {
			assertThat(d.id()).isEqualTo(dInactivoActivo);
			assertThat(d.activo()).isFalse();
		});
	}

	@Test
	void unDeportistaInactivoConVinculoRevocadoEstaOcultoEnListadoYDetalle() {
		assertThat(lista(usuarioFa1)).extracting(DeportistaDeFamilia::id).doesNotContain(dInactivoRevocado);
		assert404Uniforme(catchThrowable(() -> portal.deportista(usuarioFa1, dInactivoRevocado)));
	}

	@Test
	void desactivarYReactivarUnDeportistaCambiaSoloElIndicadorActivoSinOcultarlo() {
		datos.desactivarDeportista(dSoloFa1);
		assertThat(portal.deportista(usuarioFa1, dSoloFa1).activo()).isFalse();
		assertThat(lista(usuarioFa1)).filteredOn(d -> d.id().equals(dSoloFa1)).singleElement()
				.satisfies(d -> assertThat(d.activo()).isFalse());

		datos.reactivarDeportista(dSoloFa1);
		assertThat(portal.deportista(usuarioFa1, dSoloFa1).activo()).isTrue();
	}

	// ---------- 404 uniforme para todo id que no es propio ----------

	@Test
	void cadaIdQueNoEsPropioDaElMismoNotFoundSinImportarPorQueNoLoEs() {
		List<UUID> noPropios = List.of(dRevocado, dPendiente, dRechazado, dInactivoRevocado, dSoloFa2, dSinVinculo, dB1, dB2,
				UUID.randomUUID(), new UUID(0, 0));

		for (UUID id : noPropios) {
			assert404Uniforme(catchThrowable(() -> portal.deportista(usuarioFa1, id)));
		}
	}

	@Test
	void enCadaEstadoDeVinculoSoloActivoDaAcceso() {
		UUID familia = datos.familia(escuelaA, "Familia de estados", true);
		UsuarioAutenticado usuario = familia(escuelaA, familia);
		UUID d = dep(escuelaA, "31000001", "Estado", "Cambiante", true);
		UUID vinculo = datos.vinculo(escuelaA, familia, d, "PENDIENTE", false, null);

		// Los cuatro estados sobre la MISMA fila: fija tambien el literal de enum del JPQL (FQN en Hibernate 7.4).
		for (String estado : List.of("PENDIENTE", "RECHAZADO", "REVOCADO")) {
			datos.estadoVinculo(vinculo, estado, adminId);
			assert404Uniforme(catchThrowable(() -> portal.deportista(usuario, d)));
			assertThat(lista(usuario)).as(estado).isEmpty();
		}
		datos.estadoVinculo(vinculo, "ACTIVO", adminId);
		assertThat(portal.deportista(usuario, d).id()).isEqualTo(d);
		assertThat(lista(usuario)).extracting(DeportistaDeFamilia::id).containsExactly(d);
		datos.estadoVinculo(vinculo, "REVOCADO", adminId);
		assert404Uniforme(catchThrowable(() -> portal.deportista(usuario, d)));
	}

	// ---------- identidad manipulada: ninguna combinacion cruza escuelas ni familias ----------

	@Test
	void unaIdentidadConLaFamiliaDeUnaEscuelaYLaEscuelaDeOtraNoVeNada() {
		var familiaDeAConEscuelaB = familia(escuelaB, fa1);
		var familiaDeBConEscuelaA = familia(escuelaA, fb1);

		assertThat(lista(familiaDeAConEscuelaB)).isEmpty();
		assertThat(lista(familiaDeBConEscuelaA)).isEmpty();
		assert404Uniforme(catchThrowable(() -> portal.deportista(familiaDeAConEscuelaB, dSoloFa1)));
		assert404Uniforme(catchThrowable(() -> portal.deportista(familiaDeBConEscuelaA, dB1)));
		assertThat(catchThrowable(() -> portal.miFamilia(familiaDeAConEscuelaB))).isInstanceOfSatisfying(
				ExcepcionNegocio.class, e -> assertThat(e.getCodigo()).isEqualTo("FAMILIA_NO_ENCONTRADA"));
		assertThat(catchThrowable(() -> portal.miFamilia(familiaDeBConEscuelaA))).isInstanceOf(ExcepcionNegocio.class);
	}

	@Test
	void unaCuentaFamiliaSinFamiliaIdNoVeNadaAunqueHayaDatos() {
		var sinFamilia = new UsuarioAutenticado(UUID.randomUUID(), escuelaA, null, Rol.FAMILIA);

		assertThat(lista(sinFamilia)).isEmpty();
		assert404Uniforme(catchThrowable(() -> portal.deportista(sinFamilia, dSoloFa1)));
		assertThat(catchThrowable(() -> portal.miFamilia(sinFamilia))).isInstanceOf(ExcepcionNegocio.class);
	}

	// ---------- mi-familia: solo los tutores activos propios ----------

	@Test
	void miFamiliaTraeSoloLosTutoresActivosDeLaPropiaFamiliaEnOrden() {
		datos.tutor(escuelaA, fa1, "Zoe", "Gomez");
		datos.tutor(escuelaA, fa1, "Ana", "Gomez");
		UUID inactivo = datos.tutor(escuelaA, fa1, "Inactivo", "Gomez");
		datos.desactivarTutor(inactivo);
		datos.tutor(escuelaA, fa2, "DeOtraFamilia", "Perez");
		datos.tutor(escuelaB, fb1, "DeOtraEscuela", "Perez");

		MiFamiliaRespuesta r = portal.miFamilia(usuarioFa1);

		assertThat(r.id()).isEqualTo(fa1);
		assertThat(r.nombreReferencia()).isEqualTo("Familia A1");
		assertThat(r.tutores()).extracting(t -> t.nombre()).containsExactly("Ana", "Zoe");
	}

	// ---------- familia inactiva: solo a nivel de repositorio ----------

	@Test
	void unaFamiliaInactivaNoDevuelveNadaEnNingunaConsultaDelRepositorioYAlReactivarlaVuelve() {
		datos.desactivarFamilia(fa1);

		assertThat(familias.buscarDelPortal(fa1, escuelaA)).isEmpty();
		assertThat(repositorio.deportistasDelPortal(fa1, escuelaA, Pagina.pedir(0, 100)).getContent()).isEmpty();
		assertThat(repositorio.deportistasDelPortal(fa1, escuelaA, Pagina.pedir(0, 100)).getTotalElements()).isZero();
		assertThat(repositorio.deportistaDelPortal(fa1, escuelaA, dSoloFa1)).isEmpty();
		// El resto de familias de la escuela sigue intacto.
		assertThat(repositorio.deportistaDelPortal(fa2, escuelaA, dSoloFa2)).isPresent();

		datos.reactivarFamilia(fa1);

		assertThat(familias.buscarDelPortal(fa1, escuelaA)).isPresent();
		assertThat(repositorio.deportistaDelPortal(fa1, escuelaA, dSoloFa1)).isPresent();
		assertThat(repositorio.deportistasDelPortal(fa1, escuelaA, Pagina.pedir(0, 100)).getTotalElements()).isEqualTo(3);
	}

	// ---------- ninguna respuesta contiene datos ajenos ----------

	@Test
	void ningunaRespuestaDeA1ContieneNombresDnisNiIdsAjenos() throws Exception {
		datos.tutor(escuelaA, fa1, "Propio", "Tutor");
		datos.tutor(escuelaA, fa2, "Ajenotutor", "Otro");
		StringBuilder todo = new StringBuilder();
		todo.append(json.writeValueAsString(portal.miFamilia(usuarioFa1)));
		todo.append(json.writeValueAsString(portal.deportistas(usuarioFa1, Pagina.pedir(0, 100))));
		for (UUID propio : List.of(dSoloFa1, dAmbas, dInactivoActivo)) {
			todo.append(json.writeValueAsString(portal.deportista(usuarioFa1, propio)));
		}
		List<String> mensajes = new ArrayList<>();
		for (UUID ajeno : List.of(dSoloFa2, dRevocado, dB1, UUID.randomUUID())) {
			Throwable e = catchThrowable(() -> portal.deportista(usuarioFa1, ajeno));
			mensajes.add(e.getMessage() + ((ExcepcionNegocio) e).getCodigo() + ((ExcepcionNegocio) e).getDetalles());
		}
		todo.append(mensajes);

		String texto = todo.toString();
		for (String ajeno : List.of("Soloafa2", "Ivan", "30000008", dSoloFa2.toString(), "Revocado", "Dario", "30000003",
				dRevocado.toString(), "Debe", "Karen", dB1.toString(), "Pendiente", "Rechazado", "Inactivorevocado",
				"Sinvinculo", "Debdos", "Ajenotutor", fa2.toString(), fb1.toString(), escuelaA.toString(),
				escuelaB.toString())) {
			assertThat(texto).as("dato ajeno en una respuesta de A1: %s", ajeno).doesNotContain(ajeno);
		}
		assertThat(texto).contains("Propio").contains("Alvarez");
	}
}
