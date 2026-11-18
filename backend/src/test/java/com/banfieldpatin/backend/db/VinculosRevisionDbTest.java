package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.deportistas.Deportista;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.deportistas.dto.DeportistaActualizacion;
import com.banfieldpatin.backend.familias.vinculos.EstadoVinculo;
import com.banfieldpatin.backend.familias.vinculos.dto.DeportistaAVincular;
import com.banfieldpatin.backend.familias.vinculos.dto.ResultadoVinculacion;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculacionRespuesta;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculosDeDeportista;

import jakarta.persistence.EntityManager;

/**
 * Revision agregada de los vinculos (V11, FE-05/U11-A) contra PostgreSQL real, sin carreras: incremento solo con cambio
 * efectivo, rechazo de revisiones obsoletas aunque la operacion sea idempotente, atomicidad del lote, aislamiento entre
 * escuelas, lectura en una misma instantanea, independencia con la revision de datos permanentes y comparacion con el
 * valor de la base aunque la entidad este en el contexto de persistencia. Las carreras estan en
 * {@link VinculosRevisionConcurrenciaDbTest}.
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class VinculosRevisionDbTest extends BaseVinculosDb {

	@Autowired
	DeportistaAdminService deportistasServicio;
	@Autowired
	DataSource fuente;
	@Autowired
	EntityManager em;

	private static void obsoleta(Throwable e) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, n -> {
			assertThat(n.getCodigo()).isEqualTo("EDICION_DESACTUALIZADA");
			assertThat(n.getMessage()).isEqualTo("El registro fue modificado. Es necesario volver a cargarlo antes de guardar.");
		});
	}

	private static void codigo(Throwable e, String codigo) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, n -> assertThat(n.getCodigo()).isEqualTo(codigo));
	}

	/** Fotografia de los vinculos y revisiones de la escuela A (para probar que algo no escribio nada). */
	private String foto() {
		return jdbc.sql("""
				SELECT coalesce(json_agg(x ORDER BY x.id::text), '[]')::text FROM (
				  SELECT fd.id, fd.estado, fd.es_principal, fd.autorizado_en, d.version_vinculos, d.version
				  FROM gestion_patin.familia_deportista fd JOIN gestion_patin.deportista d ON d.id = fd.deportista_id
				  WHERE fd.escuela_id = :e) x
				""").param("e", escuelaA).query(String.class).single() + revisionesDeLaEscuela(escuelaA)
				+ contar("auditoria", escuelaA);
	}

	private String revisionesDeLaEscuela(UUID escuela) {
		return jdbc.sql("SELECT coalesce(string_agg(id::text || '=' || version_vinculos || '/' || version, ',' ORDER BY id::text), '') "
				+ "FROM gestion_patin.deportista WHERE escuela_id = :e").param("e", escuela).query(String.class).single();
	}

	// ---------- incremento y respuestas ----------

	@Test
	void elIncrementoDeLaRevisionNuncaCruzaDeEscuelaAunqueRecibaElIdDeUnDeportistaAjeno() {
		// Defensa en profundidad del UPDATE: los flujos solo le pasan ids ya bloqueados en la escuela del actor, pero el
		// predicado de escuela no debe depender de eso.
		UUID ajeno = deportista(escuelaB, "Ajeno", true);
		String antesDeB = revisionesDeLaEscuela(escuelaB);
		String antesDeA = revisionesDeLaEscuela(escuelaA);

		Integer filas = transaccion.execute(s -> repositorio.incrementarRevisionVinculos(escuelaA, List.of(ajeno)));

		assertThat(filas).isZero();
		assertThat(revisionesDeLaEscuela(escuelaB)).isEqualTo(antesDeB);
		assertThat(revisionesDeLaEscuela(escuelaA)).isEqualTo(antesDeA);
	}

	@Test
	void cadaEscrituraEfectivaAvanzaUnaVezLaRevisionYLaRespuestaTraeLaPosteriorSinTocarLaVersionDeDatos() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");
		long version = versionDeDatos(d);

		VinculacionRespuesta creado = servicio.vincular(admin, f1, List.of(new DeportistaAVincular(d, 0L)), DATOS);
		assertThat(creado.resultados().get(0).vinculo().versionVinculos()).isEqualTo("1");
		VinculacionRespuesta otro = servicio.vincular(admin, f2, List.of(new DeportistaAVincular(d, 1L)), DATOS);
		assertThat(otro.resultados().get(0).vinculo().versionVinculos()).isEqualTo("2");
		VinculoRespuesta principal = servicio.cambiarPrincipal(admin, f2, d, 2L, DATOS);
		assertThat(principal.versionVinculos()).as("una sola vez aunque se escriban dos filas").isEqualTo("3");
		VinculoRespuesta revocado = servicio.revocar(admin, f2, d, 3L, DATOS);
		assertThat(revocado.versionVinculos()).isEqualTo("4");
		assertThat(revocado.estado()).isEqualTo(EstadoVinculo.REVOCADO);
		VinculacionRespuesta reactivado = servicio.vincular(admin, f2, List.of(new DeportistaAVincular(d, 4L)), DATOS);
		assertThat(reactivado.resultados().get(0).resultado()).isEqualTo(ResultadoVinculacion.REACTIVADO);
		assertThat(reactivado.resultados().get(0).vinculo().versionVinculos()).isEqualTo("5");

		assertThat(revision(d)).isEqualTo(5);
		assertThat(versionDeDatos(d)).as("las operaciones de vinculos no mueven la revision de datos").isEqualTo(version);
		assertThat(servicio.listarDeDeportista(admin, d)).extracting(VinculoRespuesta::versionVinculos).containsOnly("5");
		assertThat(servicio.listarDeFamilia(admin, f1)).extracting(VinculoRespuesta::versionVinculos).containsOnly("5");
		assertThat(contarAuditoria("VINCULO_ACTIVADO") + contarAuditoria("VINCULO_PRINCIPAL_CAMBIADO")
				+ contarAuditoria("VINCULO_REVOCADO")).isEqualTo(5);
	}

	// ---------- escenario 7: idempotentes con la revision vigente y con una obsoleta ----------

	@Test
	void lasRepeticionesIdempotentesConLaRevisionVigenteNoEscribenNiAuditanNiMuevenLaRevisionYConUnaObsoletaDan409() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");
		vigente.vincular(admin, f1, List.of(d), DATOS);
		vigente.vincular(admin, f2, List.of(d), DATOS);
		vigente.revocar(admin, f2, d, DATOS);
		long r = revision(d);
		String antes = foto();

		assertThat(servicio.revocar(admin, f2, d, r, DATOS).estado()).isEqualTo(EstadoVinculo.REVOCADO);
		assertThat(servicio.cambiarPrincipal(admin, f1, d, r, DATOS).esPrincipal()).isTrue();
		assertThat(servicio.vincular(admin, f1, List.of(new DeportistaAVincular(d, r)), DATOS).resultados())
				.extracting(VinculacionRespuesta.Resultado::resultado).containsExactly(ResultadoVinculacion.SIN_CAMBIOS);
		assertThat(foto()).as("sin escrituras, auditoria ni revision").isEqualTo(antes);

		obsoleta(catchThrowable(() -> servicio.revocar(admin, f2, d, r - 1, DATOS)));
		obsoleta(catchThrowable(() -> servicio.cambiarPrincipal(admin, f1, d, r - 1, DATOS)));
		obsoleta(catchThrowable(() -> servicio.vincular(admin, f1, List.of(new DeportistaAVincular(d, r + 1)), DATOS)));
		assertThat(foto()).isEqualTo(antes);
	}

	// ---------- escenario 8: lote de 50 con un elemento que falla ----------

	@Test
	void unLoteDeCincuentaConUnElementoInexistenteObsoletoOInactivoNoEscribeNadaYSenalaSuPosicion() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 49; i++) {
			ids.add(deportista("L" + i));
		}
		UUID yaVinculado = ids.get(10);
		vigente.vincular(admin, f1, List.of(yaVinculado), DATOS);
		UUID inactivo = deportista(escuelaA, "Inactivo", false);
		UUID ultimo = deportista("Ultimo");
		String antes = foto();

		List<DeportistaAVincular> conInexistente = new ArrayList<>(vigente.vigentes(ids));
		conInexistente.add(new DeportistaAVincular(UUID.randomUUID(), 0L));
		Throwable noExiste = catchThrowable(() -> servicio.vincular(admin, f1, conInexistente, DATOS));
		codigo(noExiste, "DEPORTISTA_NO_ENCONTRADO");
		assertThat(((ExcepcionNegocio) noExiste).getDetalles()).extracting(x -> x.campo()).containsExactly("deportistas[49]");

		List<DeportistaAVincular> conObsoleta = new ArrayList<>(vigente.vigentes(ids));
		conObsoleta.set(10, new DeportistaAVincular(yaVinculado, 0L)); // ya avanzo a 1 y daria SIN_CAMBIOS
		conObsoleta.add(new DeportistaAVincular(ultimo, 0L));
		Throwable vieja = catchThrowable(() -> servicio.vincular(admin, f1, conObsoleta, DATOS));
		obsoleta(vieja);
		assertThat(((ExcepcionNegocio) vieja).getDetalles()).extracting(x -> x.campo()).containsExactly("deportistas[10]");

		List<DeportistaAVincular> conInactivo = new ArrayList<>(vigente.vigentes(ids));
		conInactivo.add(new DeportistaAVincular(inactivo, 0L));
		Throwable inactiva = catchThrowable(() -> servicio.vincular(admin, f1, conInactivo, DATOS));
		codigo(inactiva, "DEPORTISTA_INACTIVO");
		assertThat(((ExcepcionNegocio) inactiva).getDetalles()).extracting(x -> x.campo()).containsExactly("deportistas[49]");

		assertThat(foto()).as("ningun vinculo, revision ni auditoria").isEqualTo(antes);
		assertThat(contar("familia_deportista", escuelaA)).isEqualTo(1);
	}

	// ---------- escenario 9: reactivacion que restaura el acceso de la familia ----------

	@Test
	void reactivarUnVinculoRevocadoPorElLoteExigeLaRevisionVigenteYRestauraElAccesoDeLaFamiliaAvanzandoUnaVez() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");
		vigente.vincular(admin, f1, List.of(d), DATOS);
		vigente.revocar(admin, f1, d, DATOS);
		long r = revision(d);
		assertThat(repositorio.deportistaEnAlcance(f1, escuelaA, d)).as("revocado: sin acceso").isEmpty();

		obsoleta(catchThrowable(() -> servicio.vincular(admin, f1, List.of(new DeportistaAVincular(d, r - 1)), DATOS)));
		assertThat(fila(f1, d).orElseThrow()).containsEntry("estado", "REVOCADO");
		assertThat(repositorio.deportistaEnAlcance(f1, escuelaA, d)).as("una revision vieja no restaura el acceso").isEmpty();
		assertThat(revision(d)).isEqualTo(r);

		VinculacionRespuesta ok = servicio.vincular(admin, f1, List.of(new DeportistaAVincular(d, r)), DATOS);

		assertThat(ok.resultados()).singleElement().satisfies(x -> {
			assertThat(x.resultado()).isEqualTo(ResultadoVinculacion.REACTIVADO);
			assertThat(x.vinculo().versionVinculos()).isEqualTo(Long.toString(r + 1));
		});
		assertThat(revision(d)).isEqualTo(r + 1);
		assertThat(repositorio.deportistaEnAlcance(f1, escuelaA, d)).as("la familia vuelve a tener acceso").isPresent();
		assertThat(contarAuditoria("VINCULO_ACTIVADO")).isEqualTo(2);
	}

	// ---------- escenario 10: aislamiento entre escuelas ----------

	@Test
	void unDeportistaDeOtraEscuelaDaEl404UniformeEnTodasLasRutasYLasRevisionesDeUnaEscuelaNoSeMuevenConLaOtra()
			throws Exception {
		UUID fA = datos.familia(escuelaA, "De A", true);
		UUID fB = datos.familia(escuelaB, "De B", true);
		UUID dA = deportista("DeA");
		UUID dB = deportista(escuelaB, "DeB", true);
		vigente.vincular(adminDeB, fB, List.of(dB), DATOS);
		String revisionesB = revisionesDeLaEscuela(escuelaB);
		UUID aleatorio = UUID.randomUUID();

		Throwable lecturaAjena = catchThrowable(() -> servicio.revisionYVinculos(admin, dB));
		Throwable lecturaAleatoria = catchThrowable(() -> servicio.revisionYVinculos(admin, aleatorio));
		codigo(lecturaAjena, "DEPORTISTA_NO_ENCONTRADO");
		assertThat(lecturaAjena).hasMessage(lecturaAleatoria.getMessage());
		for (long revisionEnviada : new long[] { 0L, 1L }) {
			Throwable revocaAjeno = catchThrowable(() -> servicio.revocar(admin, fB, dB, revisionEnviada, DATOS));
			Throwable revocaAleatorio = catchThrowable(() -> servicio.revocar(admin, fB, aleatorio, revisionEnviada, DATOS));
			codigo(revocaAjeno, "VINCULO_NO_ENCONTRADO");
			assertThat(revocaAjeno).hasMessage(revocaAleatorio.getMessage());
			Throwable principalAjeno = catchThrowable(() -> servicio.cambiarPrincipal(admin, fA, dB, revisionEnviada, DATOS));
			Throwable principalAleatorio = catchThrowable(
					() -> servicio.cambiarPrincipal(admin, fA, aleatorio, revisionEnviada, DATOS));
			codigo(principalAjeno, "VINCULO_NO_ENCONTRADO");
			assertThat(principalAjeno).hasMessage(principalAleatorio.getMessage());
			Throwable loteAjeno = catchThrowable(
					() -> servicio.vincular(admin, fA, List.of(new DeportistaAVincular(dB, revisionEnviada)), DATOS));
			codigo(loteAjeno, "DEPORTISTA_NO_ENCONTRADO");
			assertThat(((ExcepcionNegocio) loteAjeno).getDetalles()).extracting(x -> x.campo()).containsExactly("deportistas[0]");
		}

		// Operaciones simultaneas en ambas escuelas: cada una mueve solo sus propias revisiones.
		List<Object> resultados = enParalelo(() -> vigente.vincular(admin, fA, List.of(dA), DATOS),
				() -> vigente.revocar(adminDeB, fB, dB, DATOS));
		assertThat(resultados).noneMatch(x -> x instanceof ExcepcionNegocio);
		assertThat(revision(dA)).isEqualTo(1);
		assertThat(revision(dB)).isEqualTo(2);
		assertThat(revisionesB).isNotEqualTo(revisionesDeLaEscuela(escuelaB));
		String revisionesBDespues = revisionesDeLaEscuela(escuelaB);
		vigente.revocar(admin, fA, dA, DATOS);
		assertThat(revisionesDeLaEscuela(escuelaB)).isEqualTo(revisionesBDespues);
	}

	// ---------- escenario 12: la lectura de la revision ----------

	@Test
	void laLecturaDevuelveLaRevisionSinVinculosConElDeportistaInactivoYReflejaCadaCambioEfectivo() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");

		VinculosDeDeportista sinVinculos = servicio.revisionYVinculos(admin, d);
		assertThat(sinVinculos.deportistaId()).isEqualTo(d);
		assertThat(sinVinculos.versionVinculos()).isEqualTo("0");
		assertThat(sinVinculos.vinculos()).isEmpty();

		vigente.vincular(admin, f1, List.of(d), DATOS);
		VinculosDeDeportista conUno = servicio.revisionYVinculos(admin, d);
		assertThat(conUno.versionVinculos()).isEqualTo("1");
		assertThat(conUno.vinculos()).singleElement().satisfies(v -> {
			assertThat(v.estado()).isEqualTo(EstadoVinculo.ACTIVO);
			assertThat(v.versionVinculos()).isEqualTo("1");
		});

		UUID inactivo = deportista(escuelaA, "Inactivo", false);
		assertThat(servicio.revisionYVinculos(admin, inactivo).versionVinculos()).isEqualTo("0");
		datos.desactivarDeportista(d);
		vigente.revocar(admin, f1, d, DATOS);
		VinculosDeDeportista inactivoRevocado = servicio.revisionYVinculos(admin, d);
		assertThat(inactivoRevocado.versionVinculos()).isEqualTo("2");
		assertThat(inactivoRevocado.vinculos()).singleElement()
				.satisfies(v -> assertThat(v.estado()).isEqualTo(EstadoVinculo.REVOCADO));
		// Repetir la revocacion (idempotente) no la mueve.
		vigente.revocar(admin, f1, d, DATOS);
		assertThat(servicio.revisionYVinculos(admin, d).versionVinculos()).isEqualTo("2");
	}

	/**
	 * Mismo instante: la lectura se detiene entre sus dos sentencias (un bloqueo de tabla sobre {@code familia}, que solo la
	 * segunda usa), otra conexion confirma una revocacion con su incremento y la lectura sigue. La respuesta es la de la
	 * instantanea de la primera sentencia: revision vieja y vinculo ACTIVO (nunca la revision vieja con el vinculo nuevo).
	 */
	@Test
	void laRevisionYLaListaSalenDeLaMismaInstantaneaAunqueOtraTransaccionConfirmeEntreLasDosSentencias() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");
		vigente.vincular(admin, f1, List.of(d), DATOS);

		VinculosDeDeportista intercalada;
		try (Connection bloqueo = fuente.getConnection()) {
			bloqueo.setAutoCommit(false);
			try (Statement st = bloqueo.createStatement()) {
				st.execute("LOCK TABLE gestion_patin.familia IN ACCESS EXCLUSIVE MODE");
			}
			Future<VinculosDeDeportista> lectura = hilos.submit(() -> servicio.revisionYVinculos(admin, d));
			try {
				esperarQueUnaConexionEsteBloqueada();
				assertThat(lectura.isDone()).isFalse();
				// El efecto de una revocacion confirmada: estado REVOCADO y revision +1, en una transaccion aparte.
				transaccion.execute(s -> {
					jdbc.sql("UPDATE gestion_patin.familia_deportista SET estado = 'REVOCADO', es_principal = false "
							+ "WHERE deportista_id = :d").param("d", d).update();
					jdbc.sql("UPDATE gestion_patin.deportista SET version_vinculos = version_vinculos + 1 WHERE id = :d")
							.param("d", d).update();
					return null;
				});
			} finally {
				bloqueo.rollback();
			}
			intercalada = lectura.get(15, TimeUnit.SECONDS);
		}

		assertThat(intercalada.versionVinculos()).isEqualTo("1");
		assertThat(intercalada.vinculos()).singleElement().satisfies(v -> {
			assertThat(v.estado()).isEqualTo(EstadoVinculo.ACTIVO);
			assertThat(v.versionVinculos()).isEqualTo("1");
		});
		VinculosDeDeportista siguiente = servicio.revisionYVinculos(admin, d);
		assertThat(siguiente.versionVinculos()).isEqualTo("2");
		assertThat(siguiente.vinculos()).singleElement().satisfies(v -> assertThat(v.estado()).isEqualTo(EstadoVinculo.REVOCADO));
	}

	// ---------- escenario 13: independencia de las dos revisiones ----------

	@Test
	void editarActivarYDesactivarElDeportistaNoMuevenLaRevisionDeVinculosYLosVinculosNoMuevenLaDeDatos() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = datos.deportista(escuelaA, "30999111", "Ana", "Sintetica");
		vigente.vincular(admin, f1, List.of(d), DATOS);
		vigente.revocar(admin, f1, d, DATOS);
		vigente.vincular(admin, f1, List.of(d), DATOS);
		assertThat(revision(d)).isEqualTo(3);
		assertThat(versionDeDatos(d)).isZero();

		deportistasServicio.actualizar(admin, d, new DeportistaActualizacion("30999111", "Ana", "Sintetica", null, null,
				null, null, null, "Banfield", null, null, null, null, 0L), DATOS);
		deportistasServicio.desactivar(admin, d, 1L, DATOS);
		deportistasServicio.activar(admin, d, 2L, DATOS);
		deportistasServicio.activar(admin, d, 3L, DATOS); // idempotente

		assertThat(versionDeDatos(d)).isEqualTo(3);
		assertThat(revision(d)).as("los cambios del deportista no tocan la revision de vinculos").isEqualTo(3);
		vigente.cambiarPrincipal(admin, f1, d, DATOS); // ya es principal: sin cambios
		vigente.revocar(admin, f1, d, DATOS);
		assertThat(revision(d)).isEqualTo(4);
		assertThat(versionDeDatos(d)).as("los vinculos no tocan la revision de datos").isEqualTo(3);
	}

	// ---------- la comparacion usa el valor de la base, no el de una entidad en cache ----------

	/**
	 * Una transaccion externa carga el {@link Deportista} (queda en el contexto de persistencia con su revision); otra
	 * transaccion confirma un vinculo nuevo (revision +1). Dentro de la MISMA transaccion externa, la escritura compara con
	 * la revision leida bajo el bloqueo, no con la de la entidad gestionada: la de la entidad es obsoleta y la vigente pasa.
	 * Son dos transacciones externas porque un 409 marca la transaccion a la que se une el servicio para revertirse.
	 */
	@Test
	void laRevisionComparadaEsLaDeLaFilaBloqueadaAunqueLaEntidadEsteEnElContextoDePersistenciaConOtroValor() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID f3 = datos.familia(escuelaA, "Tres", true);
		UUID d = deportista("Uno");
		vigente.vincular(admin, f1, List.of(d), DATOS);

		java.util.concurrent.atomic.AtomicReference<Throwable> conLaDeCache = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicLong enCache = new java.util.concurrent.atomic.AtomicLong(-1);
		Throwable alConfirmar = catchThrowable(() -> transaccion.execute(s -> {
			enCache.set(em.find(Deportista.class, d).getVersionVinculos());
			confirmarEnOtraTransaccion(() -> vigente.vincular(admin, f2, List.of(d), DATOS));
			conLaDeCache.set(catchThrowable(() -> servicio.cambiarPrincipal(admin, f2, d, enCache.get(), DATOS)));
			return null;
		}));
		assertThat(enCache.get()).isEqualTo(1L);
		obsoleta(conLaDeCache.get());
		assertThat(alConfirmar).as("el 409 revirtio la transaccion externa").isNotNull();
		assertThat(revision(d)).isEqualTo(2);

		Map<String, Object> resultado = transaccion.execute(s -> {
			long cache = em.find(Deportista.class, d).getVersionVinculos();
			confirmarEnOtraTransaccion(() -> vigente.vincular(admin, f3, List.of(d), DATOS));
			VinculoRespuesta conLaVigente = servicio.cambiarPrincipal(admin, f2, d, cache + 1, DATOS);
			return Map.of("enCache", cache, "entidadDespues", em.find(Deportista.class, d).getVersionVinculos(),
					"respuesta", conLaVigente);
		});

		assertThat(resultado.get("enCache")).isEqualTo(2L);
		assertThat(resultado.get("entidadDespues")).as("la entidad gestionada sigue con el valor viejo").isEqualTo(2L);
		assertThat(((VinculoRespuesta) resultado.get("respuesta")).versionVinculos()).isEqualTo("4");
		assertThat(revision(d)).isEqualTo(4);
		assertThat(fila(f2, d).orElseThrow()).containsEntry("es_principal", true);
		verificarInvariantes();
	}

	private void confirmarEnOtraTransaccion(java.util.function.Supplier<Object> operacion) {
		try {
			hilos.submit(operacion::get).get(15, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
