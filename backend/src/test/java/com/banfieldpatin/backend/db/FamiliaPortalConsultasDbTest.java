package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.portal.FamiliaPortalService;
import com.banfieldpatin.backend.familias.portal.dto.DeportistaDeFamilia;
import com.banfieldpatin.backend.familias.portal.dto.DeportistaDeFamiliaDetalle;
import com.banfieldpatin.backend.familias.portal.dto.MiFamiliaRespuesta;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/**
 * Consultas del portal de FAMILIA contra PostgreSQL real: techos de sentencias de Hibernate con mas de 5 filas sembradas
 * (un N+1 se nota), paginacion acotada (XC-03) y orden {@code lower(apellido), lower(nombre), id}, donde el desempate por id
 * se compara por TEXTO hexadecimal (PostgreSQL ordena los uuid sin signo, byte a byte; {@code UUID.compareTo} de Java es con
 * signo).
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class FamiliaPortalConsultasDbTest extends BaseVinculosDb {

	@Autowired
	FamiliaPortalService portal;

	private UUID familia;
	private UsuarioAutenticado usuario;
	private final List<UUID> propios = new ArrayList<>();

	@BeforeEach
	void sembrar() {
		familia = datos.familia(escuelaA, "Familia con muchos", true);
		usuario = new UsuarioAutenticado(UUID.randomUUID(), escuelaA, familia, Rol.FAMILIA);
		int dni = 32000000;
		// 7 deportistas con vinculo ACTIVO (uno inactivo), todos con el MISMO apellido y nombre salvo dos, para ejercitar el
		// desempate por id y el orden sin distinguir mayusculas.
		for (int i = 0; i < 7; i++) {
			String apellido = i < 5 ? (i % 2 == 0 ? "Mismo" : "mismo") : "Otro";
			UUID d = datos.deportista(escuelaA, String.valueOf(dni++), i < 5 ? "Igual" : "Nombre" + i, apellido, i != 3);
			datos.vinculo(escuelaA, familia, d, "ACTIVO", i == 0, adminId);
			propios.add(d);
		}
		// Ruido que NO debe contar: revocado, pendiente, de otra familia y de otra escuela.
		datos.vinculo(escuelaA, familia, datos.deportista(escuelaA, String.valueOf(dni++), "Rev", "Ruido", true), "REVOCADO",
				false, adminId);
		datos.vinculo(escuelaA, familia, datos.deportista(escuelaA, String.valueOf(dni++), "Pen", "Ruido", true),
				"PENDIENTE", false, null);
		UUID otraFamilia = datos.familia(escuelaA, "Otra", true);
		datos.vinculo(escuelaA, otraFamilia, datos.deportista(escuelaA, String.valueOf(dni++), "Aj", "Ruido", true),
				"ACTIVO", true, adminId);
		UUID familiaB = datos.familia(escuelaB, "De B", true);
		datos.vinculo(escuelaB, familiaB, datos.deportista(escuelaB, String.valueOf(dni++), "B", "Ruido", true), "ACTIVO",
				true, adminDeB.id());
	}

	// ---------- techos de sentencias ----------

	@Test
	void miFamiliaUsaComoMaximoDosSentenciasConMasDeCincoTutores() {
		for (int i = 0; i < 6; i++) {
			datos.tutor(escuelaA, familia, "Tutor" + i, "Apellido" + i);
		}
		MiFamiliaRespuesta[] respuesta = new MiFamiliaRespuesta[1];

		long sentencias = sentencias(() -> respuesta[0] = portal.miFamilia(usuario));

		assertThat(sentencias).isLessThanOrEqualTo(2);
		assertThat(respuesta[0].tutores()).hasSize(6);
	}

	@Test
	void elListadoUsaComoMaximoDosSentenciasConCualquierPaginaOTamanio() {
		for (int[] pt : new int[][] { { 0, 20 }, { 0, 3 }, { 1, 3 }, { 2, 3 }, { 3, 3 }, { 0, 1 }, { 6, 1 }, { 0, 100 },
				{ 0, 7 }, { 5, 5 } }) {
			Pagina<DeportistaDeFamilia>[] pagina = new Pagina[1];

			long sentencias = sentencias(() -> pagina[0] = portal.deportistas(usuario, Pagina.pedir(pt[0], pt[1])));

			assertThat(sentencias).as("page=%d size=%d", pt[0], pt[1]).isLessThanOrEqualTo(2);
			assertThat(pagina[0].totalElementos()).as("total con page=%d size=%d", pt[0], pt[1]).isEqualTo(7);
		}
	}

	@Test
	void elDetalleUsaUnaSolaSentencia() {
		DeportistaDeFamiliaDetalle[] detalle = new DeportistaDeFamiliaDetalle[1];

		long sentencias = sentencias(() -> detalle[0] = portal.deportista(usuario, propios.get(2)));

		assertThat(sentencias).isEqualTo(1);
		assertThat(detalle[0].id()).isEqualTo(propios.get(2));
	}

	@Test
	void unDetalleQueNoEsPropioTambienCuestaUnaSolaSentenciaYUnaFamiliaSinResultadoDosComoMaximo() {
		UUID ajeno = UUID.randomUUID();

		long detalle = sentencias(() -> org.assertj.core.api.Assertions.catchThrowable(() -> portal.deportista(usuario, ajeno)));
		long miFamilia = sentencias(() -> org.assertj.core.api.Assertions.catchThrowable(() -> portal.miFamilia(
				new UsuarioAutenticado(UUID.randomUUID(), escuelaA, UUID.randomUUID(), Rol.FAMILIA))));

		assertThat(detalle).isEqualTo(1);
		assertThat(miFamilia).isEqualTo(1);
	}

	// ---------- paginacion acotada ----------

	@Test
	void elTamanioYLaPaginaSeAcotanComoEnElRestoDeLaApiYNuncaDan400() {
		assertThat(portal.deportistas(usuario, Pagina.pedir(0, 500)).tamanio()).isEqualTo(Pagina.TAMANIO_MAXIMO);
		assertThat(portal.deportistas(usuario, Pagina.pedir(0, 0)).tamanio()).isEqualTo(1);
		assertThat(portal.deportistas(usuario, Pagina.pedir(-3, 5)).pagina()).isZero();
		assertThat(portal.deportistas(usuario, Pagina.pedir(0, 0)).contenido()).hasSize(1);
		assertThat(portal.deportistas(usuario, Pagina.pedir(0, 500)).contenido()).hasSize(7);
	}

	@Test
	void unaPaginaMasAllaDelFinalEsVaciaConElTotalCorrecto() {
		Pagina<DeportistaDeFamilia> pagina = portal.deportistas(usuario, Pagina.pedir(50, 20));

		assertThat(pagina.contenido()).isEmpty();
		assertThat(pagina.totalElementos()).isEqualTo(7);
	}

	@Test
	void paginarRecorreExactamenteLosSieteSinRepetirNiSaltar() {
		List<UUID> recorridos = new ArrayList<>();
		for (int pagina = 0; pagina < 4; pagina++) {
			portal.deportistas(usuario, Pagina.pedir(pagina, 2)).contenido().forEach(d -> recorridos.add(d.id()));
		}

		assertThat(recorridos).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(propios);
		assertThat(recorridos).isEqualTo(portal.deportistas(usuario, Pagina.pedir(0, 100)).contenido().stream()
				.map(DeportistaDeFamilia::id).toList());
	}

	// ---------- orden ----------

	@Test
	void elOrdenEsApellidoNombreSinDistinguirMayusculasYDespuesElIdPorTextoHexadecimal() {
		List<DeportistaDeFamilia> lista = portal.deportistas(usuario, Pagina.pedir(0, 100)).contenido();

		// Los cinco "Igual Mismo/mismo" empatan en lower(apellido), lower(nombre): se desempatan por id (texto hex).
		List<UUID> empatados = lista.stream().filter(d -> d.apellido().equalsIgnoreCase("mismo")).map(DeportistaDeFamilia::id)
				.toList();
		assertThat(empatados).hasSize(5).isSortedAccordingTo(Comparator.comparing(UUID::toString));
		// Los apellidos "Mismo"/"mismo" van antes que "Otro", y entre los de Otro por nombre (Nombre5 < Nombre6).
		assertThat(lista).extracting(d -> d.apellido().toLowerCase()).containsExactly("mismo", "mismo", "mismo", "mismo",
				"mismo", "otro", "otro");
		assertThat(lista.subList(5, 7)).extracting(DeportistaDeFamilia::nombre).containsExactly("Nombre5", "Nombre6");
	}
}
