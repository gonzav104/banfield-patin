package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.familias.vinculos.EstadoVinculo;
import com.banfieldpatin.backend.familias.vinculos.dto.DeportistaAVincular;
import com.banfieldpatin.backend.familias.vinculos.dto.ResultadoVinculacion;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculacionRespuesta;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta;

/**
 * Carreras sobre la revision agregada de los vinculos (FE-05/U11-A) contra PostgreSQL real. Dos operaciones PREPARADAS
 * con la misma revision del mismo deportista: la que obtiene el bloqueo primero gana y la otra, al obtenerlo, compara con
 * la revision ya avanzada y recibe 409 EDICION_DESACTUALIZADA (nunca CONFLICTO_CONCURRENCIA si el bloqueo llega a tiempo).
 * Tecnica determinista de {@link BaseVinculosDb#bBloqueadaDetrasDeA} (B espera de verdad en {@code pg_stat_activity}) y
 * rondas de hilos libres. Tras cada carrera: invariantes de la base y la revision avanzada exactamente una vez por cada
 * operacion efectiva.
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class VinculosRevisionConcurrenciaDbTest extends BaseVinculosDb {

	private static final int RONDAS = 10;

	private static void esObsoleta(Object resultado) {
		assertThat(resultado).isInstanceOfSatisfying(ExcepcionNegocio.class,
				e -> assertThat(e.getCodigo()).isEqualTo("EDICION_DESACTUALIZADA"));
	}

	private static boolean esExito(Object resultado) {
		return !(resultado instanceof ExcepcionNegocio);
	}

	private long auditoriasDeVinculos() {
		return contarAuditoria("VINCULO_ACTIVADO") + contarAuditoria("VINCULO_REVOCADO")
				+ contarAuditoria("VINCULO_PRINCIPAL_CAMBIADO");
	}

	// ---------- escenario 2: dos revocaciones del mismo vinculo con la misma revision ----------

	@Test
	void dosRevocacionesDelMismoVinculoConLaMismaRevisionDanUn200YUn409EdicionDesactualizadaYAvanzanUnaVez()
			throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");
		vigente.vincular(admin, f1, List.of(d), DATOS);
		long r = revision(d);
		long auditorias = auditoriasDeVinculos();

		Par par = bBloqueadaDetrasDeA(() -> servicio.revocar(admin, f1, d, r, DATOS),
				() -> servicio.revocar(admin, f1, d, r, DATOS));

		assertThat(((VinculoRespuesta) par.a()).estado()).isEqualTo(EstadoVinculo.REVOCADO);
		assertThat(((VinculoRespuesta) par.a()).versionVinculos()).isEqualTo(Long.toString(r + 1));
		esObsoleta(par.b());
		assertThat(revision(d)).isEqualTo(r + 1);
		assertThat(auditoriasDeVinculos()).isEqualTo(auditorias + 1);
		verificarInvariantes();
	}

	@Test
	void rondasLibresDeDosRevocacionesConLaMismaRevisionDejanSiempreUnGanadorYUnaObsoleta() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			UUID d = deportista("R" + ronda);
			vigente.vincular(admin, f1, List.of(d), DATOS);
			long r = revision(d);

			List<Object> resultados = enParalelo(() -> servicio.revocar(admin, f1, d, r, DATOS),
					() -> servicio.revocar(admin, f1, d, r, DATOS));

			assertThat(resultados.stream().filter(VinculosRevisionConcurrenciaDbTest::esExito)).as("ronda %d", ronda)
					.hasSize(1);
			resultados.stream().filter(x -> !esExito(x)).forEach(VinculosRevisionConcurrenciaDbTest::esObsoleta);
			assertThat(revision(d)).as("ronda %d", ronda).isEqualTo(r + 1);
			assertThat(fila(f1, d).orElseThrow()).containsEntry("estado", "REVOCADO");
		}
		assertThat(contarAuditoria("VINCULO_REVOCADO")).isEqualTo(RONDAS);
		verificarInvariantes();
	}

	// ---------- escenario 3: dos cambios de principal a familias distintas con la misma revision ----------

	@Test
	void dosCambiosDePrincipalAFamiliasDistintasConLaMismaRevisionDejanUnGanadorUnSoloPrincipalYUnIncremento()
			throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID f3 = datos.familia(escuelaA, "Tres", true);
		UUID d = deportista("Uno");
		for (UUID f : List.of(f1, f2, f3)) {
			vigente.vincular(admin, f, List.of(d), DATOS);
		}
		long r = revision(d);

		Par par = bBloqueadaDetrasDeA(() -> servicio.cambiarPrincipal(admin, f2, d, r, DATOS),
				() -> servicio.cambiarPrincipal(admin, f3, d, r, DATOS));

		assertThat(((VinculoRespuesta) par.a()).esPrincipal()).isTrue();
		esObsoleta(par.b());
		assertThat(fila(f2, d).orElseThrow()).containsEntry("es_principal", true);
		assertThat(fila(f3, d).orElseThrow()).containsEntry("es_principal", false);
		assertThat(principalesActivos(d)).isEqualTo(1);
		assertThat(revision(d)).isEqualTo(r + 1);
		assertThat(contarAuditoria("VINCULO_PRINCIPAL_CAMBIADO")).isEqualTo(1);
		verificarInvariantes();
	}

	@Test
	void rondasLibresDeCambiosDePrincipalConLaMismaRevisionDejanUnGanadorYExactamenteUnPrincipal() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID f3 = datos.familia(escuelaA, "Tres", true);
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			UUID d = deportista("P" + ronda);
			for (UUID f : List.of(f1, f2, f3)) {
				vigente.vincular(admin, f, List.of(d), DATOS);
			}
			long r = revision(d);

			List<Object> resultados = enParalelo(() -> servicio.cambiarPrincipal(admin, f2, d, r, DATOS),
					() -> servicio.cambiarPrincipal(admin, f3, d, r, DATOS));

			assertThat(resultados.stream().filter(VinculosRevisionConcurrenciaDbTest::esExito)).as("ronda %d", ronda)
					.hasSize(1);
			resultados.stream().filter(x -> !esExito(x)).forEach(VinculosRevisionConcurrenciaDbTest::esObsoleta);
			assertThat(principalesActivos(d)).as("ronda %d", ronda).isEqualTo(1);
			assertThat(revision(d)).as("ronda %d", ronda).isEqualTo(r + 1);
		}
		assertThat(contarAuditoria("VINCULO_PRINCIPAL_CAMBIADO")).isEqualTo(RONDAS);
		verificarInvariantes();
	}

	// ---------- escenario 4: revocar el principal contra promover otro ----------

	@Test
	void revocarElPrincipalContraPromoverOtroConLaMismaRevisionSeSerializanEnAmbosOrdenesSinPromocionAutomatica()
			throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);

		// Revocar gana: el principal se revoca, nadie se promueve y la promocion preparada antes es obsoleta.
		UUID d = deportista("Uno");
		vigente.vincular(admin, f1, List.of(d), DATOS);
		vigente.vincular(admin, f2, List.of(d), DATOS);
		long r = revision(d);
		Par revocaPrimero = bBloqueadaDetrasDeA(() -> servicio.revocar(admin, f1, d, r, DATOS),
				() -> servicio.cambiarPrincipal(admin, f2, d, r, DATOS));
		assertThat(((VinculoRespuesta) revocaPrimero.a()).estado()).isEqualTo(EstadoVinculo.REVOCADO);
		esObsoleta(revocaPrimero.b());
		assertThat(principalesActivos(d)).as("revocar no promueve").isZero();
		assertThat(fila(f2, d).orElseThrow()).containsEntry("estado", "ACTIVO").containsEntry("es_principal", false);
		assertThat(revision(d)).isEqualTo(r + 1);

		// Promover gana: el elegido pasa a principal y la revocacion preparada antes es obsoleta (el vinculo sigue ACTIVO).
		UUID e = deportista("Dos");
		vigente.vincular(admin, f1, List.of(e), DATOS);
		vigente.vincular(admin, f2, List.of(e), DATOS);
		long s = revision(e);
		Par promuevePrimero = bBloqueadaDetrasDeA(() -> servicio.cambiarPrincipal(admin, f2, e, s, DATOS),
				() -> servicio.revocar(admin, f1, e, s, DATOS));
		assertThat(((VinculoRespuesta) promuevePrimero.a()).esPrincipal()).isTrue();
		esObsoleta(promuevePrimero.b());
		assertThat(fila(f1, e).orElseThrow()).containsEntry("estado", "ACTIVO").containsEntry("es_principal", false);
		assertThat(fila(f2, e).orElseThrow()).containsEntry("es_principal", true);
		assertThat(revision(e)).isEqualTo(s + 1);
		verificarInvariantes();
	}

	// ---------- escenario 5: lote contra revocar/principal y lotes superpuestos ----------

	@Test
	void unLoteContraUnaRevocacionSobreUnDeportistaComunConLaMismaRevisionDejaUnGanadorYElLotePerdedorNoEscribeNada()
			throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID libre = deportista("Libre");
		UUID comun = deportista("Comun");
		vigente.vincular(admin, f2, List.of(comun), DATOS);
		long rComun = revision(comun);
		List<DeportistaAVincular> lote = List.of(new DeportistaAVincular(libre, 0L), new DeportistaAVincular(comun, rComun));

		// El lote gana: la revocacion preparada con la revision anterior es obsoleta y no revoca.
		Par lotePrimero = bBloqueadaDetrasDeA(() -> servicio.vincular(admin, f1, lote, DATOS),
				() -> servicio.revocar(admin, f2, comun, rComun, DATOS));
		assertThat(((VinculacionRespuesta) lotePrimero.a()).resultados()).extracting(VinculacionRespuesta.Resultado::resultado)
				.containsExactly(ResultadoVinculacion.CREADO, ResultadoVinculacion.CREADO);
		esObsoleta(lotePrimero.b());
		assertThat(fila(f2, comun).orElseThrow()).containsEntry("estado", "ACTIVO");
		assertThat(revision(comun)).isEqualTo(rComun + 1);
		assertThat(revision(libre)).isEqualTo(1);

		// La revocacion gana: el lote preparado antes es obsoleto y NO escribe ni siquiera el deportista libre.
		UUID otroLibre = deportista("OtroLibre");
		long r2 = revision(comun);
		long auditorias = auditoriasDeVinculos();
		Par revocaPrimero = bBloqueadaDetrasDeA(() -> servicio.revocar(admin, f2, comun, r2, DATOS),
				() -> servicio.vincular(admin, f1,
						List.of(new DeportistaAVincular(otroLibre, 0L), new DeportistaAVincular(comun, r2)), DATOS));
		assertThat(((VinculoRespuesta) revocaPrimero.a()).estado()).isEqualTo(EstadoVinculo.REVOCADO);
		esObsoleta(revocaPrimero.b());
		assertThat(((ExcepcionNegocio) revocaPrimero.b()).getDetalles()).extracting(x -> x.campo())
				.containsExactly("deportistas[1]");
		assertThat(fila(f1, otroLibre)).as("todo o nada").isEmpty();
		assertThat(revision(otroLibre)).isZero();
		assertThat(revision(comun)).isEqualTo(r2 + 1);
		assertThat(auditoriasDeVinculos()).isEqualTo(auditorias + 1);
		verificarInvariantes();
	}

	@Test
	void unLoteContraUnCambioDePrincipalSobreUnDeportistaComunConLaMismaRevisionDejaUnGanador() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID f3 = datos.familia(escuelaA, "Tres", true);
		UUID comun = deportista("Comun");
		vigente.vincular(admin, f1, List.of(comun), DATOS);
		vigente.vincular(admin, f2, List.of(comun), DATOS);
		long r = revision(comun);

		Par par = bBloqueadaDetrasDeA(() -> servicio.cambiarPrincipal(admin, f2, comun, r, DATOS),
				() -> servicio.vincular(admin, f3, List.of(new DeportistaAVincular(comun, r)), DATOS));

		assertThat(((VinculoRespuesta) par.a()).esPrincipal()).isTrue();
		esObsoleta(par.b());
		assertThat(fila(f3, comun)).isEmpty();
		assertThat(principalesActivos(comun)).isEqualTo(1);
		assertThat(revision(comun)).isEqualTo(r + 1);
		verificarInvariantes();
	}

	@Test
	void lotesSuperpuestosEnOrdenInversoConLasMismasRevisionesNoSeBloqueanEntreSiYDejanUnSoloLoteEscrito()
			throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			List<UUID> ids = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				ids.add(deportista("S" + ronda + "x" + i));
			}
			List<UUID> inverso = new ArrayList<>(ids);
			Collections.reverse(inverso);
			List<DeportistaAVincular> directo = vigente.vigentes(ids);
			List<DeportistaAVincular> alReves = vigente.vigentes(inverso);

			// Un deadlock (40P01) habria hecho fallar enParalelo con una excepcion que no es de negocio.
			List<Object> resultados = enParalelo(() -> servicio.vincular(admin, f1, directo, DATOS),
					() -> servicio.vincular(admin, f2, alReves, DATOS));

			assertThat(resultados.stream().filter(VinculosRevisionConcurrenciaDbTest::esExito)).as("ronda %d", ronda)
					.hasSize(1);
			resultados.stream().filter(x -> !esExito(x)).forEach(VinculosRevisionConcurrenciaDbTest::esObsoleta);
			for (UUID d : ids) {
				assertThat(filas(d)).as("ronda %d: solo el lote ganador escribio", ronda).hasSize(1);
				assertThat(revision(d)).as("ronda %d", ronda).isEqualTo(1);
				assertThat(principalesActivos(d)).isEqualTo(1);
			}
		}
		assertThat(contarAuditoria("VINCULO_ACTIVADO")).isEqualTo(5L * RONDAS);
		verificarInvariantes();
	}
}
