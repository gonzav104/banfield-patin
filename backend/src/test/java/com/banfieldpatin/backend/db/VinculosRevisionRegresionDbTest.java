package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.DetalleError;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.deportistas.Deportista;
import com.banfieldpatin.backend.familias.vinculos.EstadoVinculo;
import com.banfieldpatin.backend.familias.vinculos.dto.DeportistaAVincular;
import com.banfieldpatin.backend.familias.vinculos.dto.ResultadoVinculacion;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculacionRespuesta;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculosDeDeportista;

import jakarta.persistence.EntityManager;

/** Sondas del verificador independiente de U11-A (servicio real, PostgreSQL descartable). */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class VinculosRevisionRegresionDbTest extends BaseVinculosDb {

	@Autowired
	EntityManager em;

	private String hash() {
		return jdbc.sql("""
				SELECT md5(coalesce((SELECT json_agg(fd ORDER BY fd.id)::text FROM gestion_patin.familia_deportista fd
				                     WHERE fd.escuela_id = :e), '')
				        || coalesce((SELECT json_agg(json_build_array(d.id, d.version, d.version_vinculos, d.activo)
				                     ORDER BY d.id)::text FROM gestion_patin.deportista d WHERE d.escuela_id = :e), '')
				        || (SELECT count(*) FROM gestion_patin.auditoria a WHERE a.escuela_id = :e)::text)
				""").param("e", escuelaA).query(String.class).single();
	}

	private static ExcepcionNegocio error(Runnable r) {
		Throwable t = catchThrowable(r::run);
		assertThat(t).isInstanceOf(ExcepcionNegocio.class);
		return (ExcepcionNegocio) t;
	}

	private static List<String> campos(ExcepcionNegocio e) {
		return e.getDetalles().stream().map(DetalleError::campo).toList();
	}

	private static DeportistaAVincular p(UUID id, long r) {
		return new DeportistaAVincular(id, r);
	}

	@Test
	void todoComandoRechazadoDejaIntactoElHashYSenalaLasPosicionesOriginalesConRepetidosDelante() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID fInactiva = datos.familia(escuelaA, "Inactiva", false);
		UUID d1 = deportista("Uno");
		UUID d2 = deportista("Dos");
		UUID d3 = deportista(escuelaA, "Tres", false);
		UUID ajeno = deportista(escuelaB, "Ajeno", true);
		vigente.vincular(admin, f1, List.of(d1), DATOS);
		vigente.vincular(admin, f2, List.of(d1), DATOS);
		long r1 = revision(d1);
		long r2 = revision(d2);
		long r3 = revision(d3);
		assertThat(r1).isEqualTo(2);
		String antes = hash();

		ExcepcionNegocio e = error(() -> servicio.vincular(admin, f2, List.of(p(d2, r2), p(d2, r2), p(UUID.randomUUID(), 0)), DATOS));
		assertThat(e.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO");
		assertThat(campos(e)).containsExactly("deportistas[2]");

		e = error(() -> servicio.vincular(admin, f2, List.of(p(d2, r2), p(d2, r2), p(ajeno, 0)), DATOS));
		assertThat(e.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO");
		assertThat(campos(e)).containsExactly("deportistas[2]");

		// d1 daria SIN_CAMBIOS con f1, pero su revision es obsoleta: 409 y nada escrito para d2.
		e = error(() -> servicio.vincular(admin, f1, List.of(p(d2, r2), p(d2, r2), p(d1, r1 - 1)), DATOS));
		assertThat(e.getCodigo()).isEqualTo("EDICION_DESACTUALIZADA");
		assertThat(campos(e)).containsExactly("deportistas[2]");
		assertThat(e.getDetalles().toString()).doesNotContain(d1.toString()).doesNotContain("mensaje=" + r1);

		e = error(() -> servicio.vincular(admin, f1, List.of(p(d1, r1 + 1), p(d1, r1 + 1)), DATOS));
		assertThat(e.getCodigo()).isEqualTo("EDICION_DESACTUALIZADA");
		assertThat(campos(e)).containsExactly("deportistas[0]", "deportistas[1]");

		// Obsoleta precede a inactivo.
		e = error(() -> servicio.vincular(admin, f1, List.of(p(d1, r1), p(d3, r3 + 5)), DATOS));
		assertThat(e.getCodigo()).isEqualTo("EDICION_DESACTUALIZADA");
		assertThat(campos(e)).containsExactly("deportistas[1]");

		e = error(() -> servicio.vincular(admin, f2, List.of(p(d2, r2), p(d2, r2), p(d3, r3)), DATOS));
		assertThat(e.getCodigo()).isEqualTo("DEPORTISTA_INACTIVO");
		assertThat(campos(e)).containsExactly("deportistas[2]");

		e = error(() -> servicio.vincular(admin, fInactiva, List.of(p(d2, r2 + 9)), DATOS));
		assertThat(e.getCodigo()).isEqualTo("FAMILIA_INACTIVA");

		e = error(() -> servicio.revocar(admin, f1, d1, r1 - 1, DATOS));
		assertThat(e.getCodigo()).isEqualTo("EDICION_DESACTUALIZADA");
		assertThat(e.getDetalles()).isEmpty();
		e = error(() -> servicio.revocar(admin, f1, d2, r2 + 3, DATOS));
		assertThat(e.getCodigo()).isEqualTo("VINCULO_NO_ENCONTRADO");
		e = error(() -> servicio.revocar(admin, f1, ajeno, 0, DATOS));
		assertThat(e.getCodigo()).isEqualTo("VINCULO_NO_ENCONTRADO");
		e = error(() -> servicio.cambiarPrincipal(admin, f2, d1, r1 + 1, DATOS));
		assertThat(e.getCodigo()).isEqualTo("EDICION_DESACTUALIZADA");
		e = error(() -> servicio.cambiarPrincipal(admin, fInactiva, d1, r1 + 1, DATOS));
		assertThat(e.getCodigo()).isEqualTo("FAMILIA_INACTIVA");
		e = error(() -> servicio.cambiarPrincipal(adminDeB, f2, d1, r1, DATOS));
		assertThat(e.getCodigo()).isEqualTo("FAMILIA_NO_ENCONTRADA");
		e = error(() -> servicio.revisionYVinculos(adminDeB, d1));
		assertThat(e.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO");

		assertThat(hash()).as("ningun rechazo escribe, audita ni mueve revisiones").isEqualTo(antes);
	}

	@Test
	void unLoteConElMismoDeportistaRepetidoLoEscribeUnaVezEnLaPrimeraPosicionYAvanzaUnaVez() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d1 = deportista("Uno");
		UUID d2 = deportista("Dos");
		long auditorias = contarAuditoria("VINCULO_ACTIVADO");

		VinculacionRespuesta r = servicio.vincular(admin, f1, List.of(p(d2, 0), p(d1, 0), p(d2, 0), p(d1, 0)), DATOS);

		assertThat(r.resultados()).extracting(x -> x.vinculo().deportistaId()).containsExactly(d2, d1);
		assertThat(r.resultados()).extracting(VinculacionRespuesta.Resultado::resultado)
				.containsExactly(ResultadoVinculacion.CREADO, ResultadoVinculacion.CREADO);
		assertThat(r.resultados()).extracting(x -> x.vinculo().versionVinculos()).containsExactly("1", "1");
		assertThat(revision(d1)).isEqualTo(1);
		assertThat(revision(d2)).isEqualTo(1);
		assertThat(contarAuditoria("VINCULO_ACTIVADO")).isEqualTo(auditorias + 2);
		assertThat(versionDeDatos(d1)).isZero();
	}

	/**
	 * La misma transaccion externa encadena lote, lote, principal y revocar: cada paso compara con la revision ya avanzada
	 * por el paso anterior (consulta escalar), aunque la entidad Deportista del contexto conserve el valor de su carga.
	 */
	@Test
	void variasEscriturasEnLaMismaTransaccionComparanConLaRevisionYaIncrementadaPorLaAnterior() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");

		List<String> revisiones = transaccion.execute(s -> {
			List<String> rs = new ArrayList<>();
			rs.add(servicio.vincular(admin, f1, List.of(p(d, 0)), DATOS).resultados().get(0).vinculo().versionVinculos());
			rs.add(servicio.vincular(admin, f2, List.of(p(d, 1), p(d, 1)), DATOS).resultados().get(0).vinculo()
					.versionVinculos());
			rs.add(servicio.cambiarPrincipal(admin, f2, d, 2, DATOS).versionVinculos());
			rs.add(servicio.cambiarPrincipal(admin, f2, d, 3, DATOS).versionVinculos()); // ya es principal
			rs.add(servicio.revocar(admin, f1, d, 3, DATOS).versionVinculos());
			rs.add(servicio.revocar(admin, f1, d, 4, DATOS).versionVinculos()); // idempotente
			rs.add(servicio.revisionYVinculos(admin, d).versionVinculos());
			return rs;
		});

		assertThat(revisiones).containsExactly("1", "2", "3", "3", "4", "4", "4");
		assertThat(revision(d)).isEqualTo(4);
		assertThat(versionDeDatos(d)).isZero();
		assertThat(fila(f2, d).orElseThrow()).containsEntry("es_principal", true);
		assertThat(fila(f1, d).orElseThrow()).containsEntry("estado", "REVOCADO").containsEntry("es_principal", false);
		verificarInvariantes();
	}

	/** Lote y revocar con la revision de una entidad cargada antes en la misma transaccion: es obsoleta y se rechaza. */
	@Test
	void loteYRevocarConLaRevisionDeUnaEntidadEnCacheObsoletaDan409() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID f3 = datos.familia(escuelaA, "Tres", true);
		UUID d = deportista("Uno");
		vigente.vincular(admin, f1, List.of(d), DATOS);

		for (int caso = 0; caso < 2; caso++) {
			int c = caso;
			AtomicReference<Throwable> interno = new AtomicReference<>();
			AtomicLong cache = new AtomicLong();
			Throwable externo = catchThrowable(() -> transaccion.execute(s -> {
				cache.set(em.find(Deportista.class, d).getVersionVinculos());
				enOtraTransaccion(() -> vigente.vincular(admin, c == 0 ? f2 : f3, List.of(d), DATOS));
				interno.set(catchThrowable(() -> {
					if (c == 0) {
						servicio.vincular(admin, f3, List.of(p(d, cache.get())), DATOS);
					} else {
						servicio.revocar(admin, f1, d, cache.get(), DATOS);
					}
				}));
				return null;
			}));
			assertThat(interno.get()).isInstanceOfSatisfying(ExcepcionNegocio.class,
					e -> assertThat(e.getCodigo()).isEqualTo("EDICION_DESACTUALIZADA"));
			assertThat(externo).isNotNull();
		}
		assertThat(revision(d)).isEqualTo(3);
		assertThat(fila(f1, d).orElseThrow()).containsEntry("estado", "ACTIVO");
		verificarInvariantes();
	}

	/** Tres escrituras distintas preparadas con la misma revision del mismo deportista: exactamente un ganador. */
	@Test
	void rondasDeLotePrincipalYRevocarConLaMismaRevisionDejanExactamenteUnGanador() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID f3 = datos.familia(escuelaA, "Tres", true);
		for (int ronda = 0; ronda < 12; ronda++) {
			UUID d = deportista("T" + ronda);
			vigente.vincular(admin, f1, List.of(d), DATOS);
			vigente.vincular(admin, f2, List.of(d), DATOS);
			long r = revision(d);
			long auditorias = contarAuditoria("VINCULO_ACTIVADO") + contarAuditoria("VINCULO_REVOCADO")
					+ contarAuditoria("VINCULO_PRINCIPAL_CAMBIADO");
			List<Supplier<Object>> tareas = new ArrayList<>(List.of(
					() -> servicio.vincular(admin, f3, List.of(p(d, r)), DATOS),
					() -> servicio.cambiarPrincipal(admin, f2, d, r, DATOS),
					() -> servicio.revocar(admin, f1, d, r, DATOS)));
			if (ronda % 2 == 1) {
				java.util.Collections.reverse(tareas);
			}
			@SuppressWarnings("unchecked")
			List<Object> res = enParalelo(tareas.toArray(new Supplier[0]));
			long exitos = res.stream().filter(o -> !(o instanceof ExcepcionNegocio)).count();
			assertThat(exitos).as("ronda " + ronda + ": " + res).isEqualTo(1);
			assertThat(res.stream().filter(o -> o instanceof ExcepcionNegocio).map(o -> ((ExcepcionNegocio) o).getCodigo()))
					.containsOnly("EDICION_DESACTUALIZADA");
			assertThat(revision(d)).isEqualTo(r + 1);
			assertThat(contarAuditoria("VINCULO_ACTIVADO") + contarAuditoria("VINCULO_REVOCADO")
					+ contarAuditoria("VINCULO_PRINCIPAL_CAMBIADO")).isEqualTo(auditorias + 1);
			assertThat(principalesActivos(d)).isLessThanOrEqualTo(1);
			verificarInvariantes();
		}
	}

	/**
	 * Lecturas libres mientras otro hilo alterna revocar/reactivar: cada respuesta es una instantanea coherente (todas las
	 * filas traen la revision de la cabecera y el estado corresponde a la paridad de la revision).
	 */
	@Test
	void lecturasLibresDuranteEscriturasDevuelvenSiempreUnaInstantaneaCoherente() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");
		vigente.vincular(admin, f1, List.of(d), DATOS); // revision 1, ACTIVO
		Future<?> escritor = hilos.submit(() -> {
			for (int i = 0; i < 40; i++) {
				if (i % 2 == 0) {
					vigente.revocar(admin, f1, d, DATOS);
				} else {
					vigente.vincular(admin, f1, List.of(d), DATOS);
				}
			}
			return null;
		});
		int lecturas = 0;
		while (!escritor.isDone() || lecturas < 20) {
			VinculosDeDeportista v = servicio.revisionYVinculos(admin, d);
			long rev = Long.parseLong(v.versionVinculos());
			assertThat(v.vinculos()).singleElement().satisfies(x -> {
				assertThat(x.versionVinculos()).isEqualTo(v.versionVinculos());
				assertThat(x.estado()).isEqualTo(rev % 2 == 1 ? EstadoVinculo.ACTIVO : EstadoVinculo.REVOCADO);
			});
			lecturas++;
		}
		escritor.get(30, TimeUnit.SECONDS);
		assertThat(revision(d)).isEqualTo(41);
	}

	/** Revisiones fuera de la cache de Long (-128..127): la comparacion del lote es por valor, no por identidad. */
	@Test
	void revisionesGrandesSeComparanPorValorEnLoteRevocarYPrincipal() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");
		UUID e = deportista("Dos");
		jdbc.sql("UPDATE gestion_patin.deportista SET version_vinculos = 1000 WHERE id IN (:d, :e)").param("d", d)
				.param("e", e).update();
		VinculacionRespuesta r = servicio.vincular(admin, f1, List.of(p(d, Long.valueOf(1000L)), p(e, Long.valueOf(1000L)), p(d, Long.valueOf(1000L))), DATOS);
		assertThat(r.resultados()).extracting(x -> x.vinculo().versionVinculos()).containsExactly("1001", "1001");
		assertThat(servicio.vincular(admin, f2, List.of(p(d, Long.valueOf(1001L))), DATOS).resultados().get(0).vinculo()
				.versionVinculos()).isEqualTo("1002");
		assertThat(servicio.cambiarPrincipal(admin, f2, d, 1002L, DATOS).versionVinculos()).isEqualTo("1003");
		assertThat(servicio.revocar(admin, f1, d, 1003L, DATOS).versionVinculos()).isEqualTo("1004");
		assertThat(revision(d)).isEqualTo(1004);
		assertThat(revision(e)).isEqualTo(1001);
	}

	private void enOtraTransaccion(Supplier<Object> operacion) {
		try {
			hilos.submit(operacion::get).get(15, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unused")
	private static VinculoRespuesta nada() {
		return null;
	}
}
