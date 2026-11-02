package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.familias.portal.FamiliaPortalTemporadasService;
import com.banfieldpatin.backend.familias.portal.dto.TemporadaDeFamilia;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.banfieldpatin.backend.usuarios.Rol;

import tools.jackson.databind.json.JsonMapper;

/**
 * Datos deportivos anuales de FAMILIA contra PostgreSQL real, con el servicio y las consultas REALES. Matriz de dos familias
 * en una escuela y una tercera en otra, con deportistas propios, compartidos, inactivos y con vinculo en cada estado, y
 * temporadas pasadas, activa y futura (borrador). Cada fila deportiva lleva un texto que identifica a su duenio, para detectar
 * cualquier filtracion entre familias, deportistas, temporadas o escuelas.
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class FamiliaPortalTemporadasAislamientoDbTest extends BaseVinculosDb {

	@Autowired
	FamiliaPortalTemporadasService portal;
	@Autowired
	JsonMapper json;

	private UUID fa1;
	private UUID fa2;
	private UUID fb1;
	private UUID t25;
	private UUID t26;
	private UUID t27;
	private UUID tb26;
	private UUID tb27;
	private UUID dPropio;
	private UUID dInactivo;
	private UUID dCompartido;
	private UUID dAjeno;
	private UUID dRevocado;
	private UUID dPendiente;
	private UUID dRechazado;
	private UUID dSinFilas;
	private UUID dSoloBorrador;
	private UUID dDeB;

	@BeforeEach
	void sembrar() {
		fa1 = datos.familia(escuelaA, "Familia Uno", true);
		fa2 = datos.familia(escuelaA, "Familia Dos", true);
		fb1 = datos.familia(escuelaB, "Familia B", true);
		t25 = datos.temporada(escuelaA, 2025, false);
		t26 = datos.temporada(escuelaA, 2026, true);
		t27 = datos.temporada(escuelaA, 2027, false);
		tb26 = datos.temporada(escuelaB, 2026, true);
		tb27 = datos.temporada(escuelaB, 2027, false);
		dPropio = datos.deportista(escuelaA, "35000001", "Lola", "Propia", true);
		dInactivo = datos.deportista(escuelaA, "35000002", "Pepe", "Inactivo", false);
		dCompartido = datos.deportista(escuelaA, "35000003", "Mara", "Compartida", true);
		dAjeno = datos.deportista(escuelaA, "35000004", "Nico", "Ajeno", true);
		dRevocado = datos.deportista(escuelaA, "35000005", "Rita", "Revocada", true);
		dPendiente = datos.deportista(escuelaA, "35000006", "Pia", "Pendiente", true);
		dRechazado = datos.deportista(escuelaA, "35000007", "Raul", "Rechazado", true);
		dSinFilas = datos.deportista(escuelaA, "35000008", "Sin", "Filas", true);
		dSoloBorrador = datos.deportista(escuelaA, "35000009", "Solo", "Borrador", true);
		dDeB = datos.deportista(escuelaB, "35000010", "Beto", "DeB", true);
		datos.vinculo(escuelaA, fa1, dPropio, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa1, dInactivo, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa1, dCompartido, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa2, dCompartido, "ACTIVO", false, adminId);
		datos.vinculo(escuelaA, fa2, dAjeno, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa1, dRevocado, "REVOCADO", false, adminId);
		datos.vinculo(escuelaA, fa1, dPendiente, "PENDIENTE", false, null);
		datos.vinculo(escuelaA, fa1, dRechazado, "RECHAZADO", false, null);
		datos.vinculo(escuelaA, fa1, dSinFilas, "ACTIVO", false, adminId);
		datos.vinculo(escuelaA, fa1, dSoloBorrador, "ACTIVO", false, adminId);
		datos.vinculo(escuelaB, fb1, dDeB, "ACTIVO", true, adminDeB.id());
		for (UUID d : List.of(dPropio, dInactivo, dCompartido, dAjeno, dRevocado, dPendiente, dRechazado)) {
			String duenio = d.toString().substring(0, 8);
			datos.datosDeportivos(escuelaA, d, t25, "cat-" + duenio + "-2025", null, "ACTIVO");
			datos.datosDeportivos(escuelaA, d, t26, "cat-" + duenio + "-2026", d.equals(dPropio) ? "007" : null,
					d.equals(dPropio) ? "SUSPENDIDO" : "ACTIVO");
			datos.datosDeportivos(escuelaA, d, t27, "cat-" + duenio + "-2027", null, "ACTIVO");
		}
		datos.datosDeportivos(escuelaA, dSoloBorrador, t27, "cat-solo-borrador-2027", null, "ACTIVO");
		datos.datosDeportivos(escuelaB, dDeB, tb26, "cat-deB-2026", null, "ACTIVO");
		datos.datosDeportivos(escuelaB, dDeB, tb27, "cat-deB-2027", null, "ACTIVO");
	}

	private static UsuarioAutenticado familia(UUID escuela, UUID familiaId) {
		return new UsuarioAutenticado(UUID.randomUUID(), escuela, familiaId, Rol.FAMILIA);
	}

	private UsuarioAutenticado usuarioFa1() {
		return familia(escuelaA, fa1);
	}

	private static void assert404(Throwable e, String codigo, String mensaje) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, n -> {
			assertThat(n.getEstado()).isEqualTo(HttpStatus.NOT_FOUND);
			assertThat(n.getCodigo()).isEqualTo(codigo);
			assertThat(n.getMessage()).isEqualTo(mensaje);
			assertThat(n.getDetalles()).isEmpty();
		});
	}

	private static void assertHistorial404(Throwable e) {
		assert404(e, "DEPORTISTA_NO_ENCONTRADO", "El deportista no existe.");
	}

	private static void assertDetalle404(Throwable e) {
		assert404(e, "DEPORTISTA_TEMPORADA_NO_ENCONTRADA", "El deportista no tiene datos cargados en esa temporada.");
	}

	private List<Integer> anios(UsuarioAutenticado u, UUID deportista) {
		return portal.historial(u, deportista).stream().map(t -> (int) t.anio()).toList();
	}

	// ---------- visibilidad ----------

	@Test
	void elHistorialVaDelAnioMasNuevoVisibleAlMasViejoYOcultaLaTemporadaBorradorFutura() {
		assertThat(anios(usuarioFa1(), dPropio)).containsExactly(2026, 2025);
	}

	@Test
	void soloLaTemporadaActivaSeMarcaComoVigenteYLosCamposSonLosDelContrato() {
		List<TemporadaDeFamilia> h = portal.historial(usuarioFa1(), dPropio);

		assertThat(h).extracting(TemporadaDeFamilia::temporadaActiva).containsExactly(true, false);
		TemporadaDeFamilia actual = h.get(0);
		assertThat(actual.temporadaId()).isEqualTo(t26);
		assertThat(actual.categoria()).isEqualTo("cat-" + dPropio.toString().substring(0, 8) + "-2026");
		assertThat(actual.rama()).isEqualTo("Femenino");
		assertThat(actual.divisional()).isEqualTo("A");
		assertThat(actual.numeroCorredor()).isEqualTo("007");
		assertThat(actual.estadoDeportivo()).isEqualTo(EstadoDeportivo.SUSPENDIDO);
	}

	@Test
	void unaTemporadaFuturaNoSeVeNiPorHistorialNiPorDetalle() {
		assertDetalle404(catchThrowable(() -> portal.temporada(usuarioFa1(), dPropio, t27)));
		assertThat(portal.temporada(usuarioFa1(), dPropio, t26).anio()).isEqualTo((short) 2026);
		assertThat(portal.temporada(usuarioFa1(), dPropio, t25).anio()).isEqualTo((short) 2025);
	}

	@Test
	void sinTemporadaActivaNoHayNingunaVisibleYElDeportistaPropioDa200VacioYElAjenoNotFound() {
		datos.activarTemporada(escuelaA, null);

		assertThat(portal.historial(usuarioFa1(), dPropio)).isEmpty();
		assertDetalle404(catchThrowable(() -> portal.temporada(usuarioFa1(), dPropio, t25)));
		assertDetalle404(catchThrowable(() -> portal.temporada(usuarioFa1(), dPropio, t26)));
		assertHistorial404(catchThrowable(() -> portal.historial(usuarioFa1(), dAjeno)));
	}

	@Test
	void activarUnaTemporadaHaceVisiblesSusDatosYReactivarUnaAnteriorOcultaLosAniosPosteriores() {
		datos.activarTemporada(escuelaA, t27);
		assertThat(anios(usuarioFa1(), dPropio)).as("activar 2027 publica sus datos").containsExactly(2027, 2026, 2025);

		datos.activarTemporada(escuelaA, t25);
		assertThat(anios(usuarioFa1(), dPropio)).as("reactivar 2025 oculta 2026 y 2027").containsExactly(2025);
		assertDetalle404(catchThrowable(() -> portal.temporada(usuarioFa1(), dPropio, t26)));

		datos.activarTemporada(escuelaA, t26);
		assertThat(anios(usuarioFa1(), dPropio)).containsExactly(2026, 2025);
	}

	@Test
	void unDeportistaPropioSinFilasOSoloConUnBorradorDa200VacioNoNotFound() {
		assertThat(portal.historial(usuarioFa1(), dSinFilas)).isEmpty();
		assertThat(portal.historial(usuarioFa1(), dSoloBorrador)).isEmpty();
	}

	// ---------- deportista globalmente inactivo ----------

	@Test
	void unDeportistaGlobalmenteInactivoConVinculoActivoMuestraSuHistorialConSuEstadoDeportivoPropio() {
		List<TemporadaDeFamilia> h = portal.historial(usuarioFa1(), dInactivo);

		assertThat(h).extracting(t -> (int) t.anio()).containsExactly(2026, 2025);
		// El estado global (activo=false) y el deportivo anual son planos distintos: aqui el anual es ACTIVO.
		assertThat(h).extracting(TemporadaDeFamilia::estadoDeportivo).containsOnly(EstadoDeportivo.ACTIVO);
		datos.desactivarDeportista(dPropio);
		assertThat(portal.historial(usuarioFa1(), dPropio).get(0).estadoDeportivo()).isEqualTo(EstadoDeportivo.SUSPENDIDO);
		datos.reactivarDeportista(dPropio);
		assertThat(portal.historial(usuarioFa1(), dPropio).get(0).estadoDeportivo()).isEqualTo(EstadoDeportivo.SUSPENDIDO);
	}

	@Test
	void unDeportistaInactivoConVinculoRevocadoNoMuestraNada() {
		datos.estadoVinculo(jdbc.sql("SELECT id FROM gestion_patin.familia_deportista WHERE deportista_id = :d AND familia_id = :f")
				.param("d", dInactivo).param("f", fa1).query(UUID.class).single(), "REVOCADO", adminId);

		assertHistorial404(catchThrowable(() -> portal.historial(usuarioFa1(), dInactivo)));
		assertDetalle404(catchThrowable(() -> portal.temporada(usuarioFa1(), dInactivo, t26)));
	}

	// ---------- aislamiento entre familias, vinculos y escuelas ----------

	@Test
	void unDeportistaCompartidoLoVenAmbasFamiliasConLosMismosDatos() {
		assertThat(portal.historial(usuarioFa1(), dCompartido)).isEqualTo(portal.historial(familia(escuelaA, fa2), dCompartido))
				.hasSize(2);
	}

	@Test
	void cadaCausaAjenaDaExactamenteElMismoNotFoundEnElHistorial() {
		for (UUID id : List.of(dAjeno /* otra familia */, dRevocado /* vinculo revocado */, dPendiente, dRechazado,
				dDeB /* otra escuela */, UUID.randomUUID() /* inexistente */, new UUID(0, 0))) {
			assertHistorial404(catchThrowable(() -> portal.historial(usuarioFa1(), id)));
		}
		// La familia de la otra escuela tampoco ve los deportistas de A.
		assertHistorial404(catchThrowable(() -> portal.historial(familia(escuelaB, fb1), dPropio)));
	}

	@Test
	void cadaCausaAjenaOInvisibleDaExactamenteElMismoNotFoundEnElDetalle() {
		List<Object[]> causas = List.of(
				new Object[] { dAjeno, t26 }, new Object[] { dRevocado, t26 }, new Object[] { dPendiente, t26 },
				new Object[] { dRechazado, t26 }, new Object[] { dDeB, tb26 }, new Object[] { dDeB, t26 },
				new Object[] { UUID.randomUUID(), t26 },
				// Deportista propio: temporada futura (borrador), de otra escuela, inexistente y sin fila para el.
				new Object[] { dPropio, t27 }, new Object[] { dPropio, tb26 }, new Object[] { dPropio, UUID.randomUUID() },
				new Object[] { dSinFilas, t26 }, new Object[] { dSoloBorrador, t27 });
		for (Object[] causa : causas) {
			assertDetalle404(catchThrowable(() -> portal.temporada(usuarioFa1(), (UUID) causa[0], (UUID) causa[1])));
		}
	}

	@Test
	void unaIdentidadConLaFamiliaDeUnaEscuelaYLaEscuelaDeOtraNoVeNada() {
		assertHistorial404(catchThrowable(() -> portal.historial(familia(escuelaB, fa1), dPropio)));
		assertHistorial404(catchThrowable(() -> portal.historial(familia(escuelaA, fb1), dDeB)));
		assertDetalle404(catchThrowable(() -> portal.temporada(familia(escuelaB, fa1), dPropio, t26)));
	}

	@Test
	void unaFamiliaInactivaNoVeNadaYAlReactivarlaVuelve() {
		jdbc.sql("UPDATE gestion_patin.familia SET activa = false WHERE id = :f").param("f", fa1).update();

		assertHistorial404(catchThrowable(() -> portal.historial(usuarioFa1(), dPropio)));
		assertDetalle404(catchThrowable(() -> portal.temporada(usuarioFa1(), dPropio, t26)));

		jdbc.sql("UPDATE gestion_patin.familia SET activa = true WHERE id = :f").param("f", fa1).update();
		assertThat(anios(usuarioFa1(), dPropio)).containsExactly(2026, 2025);
	}

	@Test
	void unaCuentaFamiliaSinFamiliaIdNoVeNadaAunqueHayaDatos() {
		UsuarioAutenticado sinFamilia = familia(escuelaA, null);

		assertHistorial404(catchThrowable(() -> portal.historial(sinFamilia, dPropio)));
		assertDetalle404(catchThrowable(() -> portal.temporada(sinFamilia, dPropio, t26)));
	}

	@Test
	void ningunaRespuestaDeLaFamiliaContieneDatosNiIdsAjenos() throws Exception {
		StringBuilder todo = new StringBuilder();
		for (UUID d : List.of(dPropio, dInactivo, dCompartido, dSinFilas, dSoloBorrador)) {
			todo.append(json.writeValueAsString(portal.historial(usuarioFa1(), d)));
			for (UUID t : List.of(t25, t26, t27)) {
				Throwable e = catchThrowable(() -> todo.append(json.writeValueAsString(portal.temporada(usuarioFa1(), d, t))));
				if (e != null) {
					todo.append(e.getMessage());
				}
			}
		}
		String texto = todo.toString();

		for (UUID ajeno : List.of(dAjeno, dRevocado, dPendiente, dRechazado, dDeB)) {
			assertThat(texto).doesNotContain(ajeno.toString()).doesNotContain("cat-" + ajeno.toString().substring(0, 8));
		}
		assertThat(texto).doesNotContain("cat-deB", "cat-solo-borrador", "-2027", "35000", escuelaA.toString(),
				escuelaB.toString(), "Propia", "Lola");
	}

	// ---------- sin N+1 ----------

	@Test
	void laCantidadDeSentenciasNoCreceConLaCantidadDeTemporadas() {
		long conDos = sentencias(() -> portal.historial(usuarioFa1(), dPropio));
		long detalle = sentencias(() -> portal.temporada(usuarioFa1(), dPropio, t26));
		long vacio = sentencias(() -> portal.historial(usuarioFa1(), dSinFilas));
		for (int anio = 2020; anio < 2025; anio++) {
			datos.datosDeportivos(escuelaA, dPropio, datos.temporada(escuelaA, anio, false), "cat-extra-" + anio, null, "ACTIVO");
		}

		assertThat(portal.historial(usuarioFa1(), dPropio)).hasSize(7);
		assertThat(sentencias(() -> portal.historial(usuarioFa1(), dPropio))).as("historial con 7 filas").isEqualTo(conDos)
				.isEqualTo(1);
		assertThat(detalle).as("detalle").isEqualTo(1);
		assertThat(vacio).as("historial vacio: la consulta mas la de alcance").isEqualTo(2);
	}
}
