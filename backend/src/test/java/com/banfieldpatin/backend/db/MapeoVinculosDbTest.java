package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.familias.vinculos.EstadoVinculo;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportista;
import com.banfieldpatin.backend.familias.vinculos.dto.ResultadoVinculacion;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta;

/**
 * Mapeo de {@code FamiliaDeportista} y de sus consultas contra PostgreSQL real (Flyway V1-V4, ddl-auto=validate): ida y
 * vuelta, enum como texto, {@code es_principal=false} que se guarda como false (nunca el DEFAULT true de V1), la fila que se
 * reutiliza al revincular, la autorizacion de todo ACTIVO y el literal de enum con nombre completo en el JPQL (fijado aqui:
 * Hibernate 7.4 lo acepta, no hizo falta el parametro enlazado de reserva).
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class MapeoVinculosDbTest extends BaseVinculosDb {

	@Test
	void unVinculoCreadoPorLaApiSeGuardaActivoConSuAutorizacionYCreadoEnDeLaBase() {
		UUID familia = datos.familia(escuelaA, "Perez", true);
		UUID d = deportista("Uno");

		var respuesta = servicio.vincular(admin, familia, List.of(d), DATOS);

		Map<String, Object> fila = fila(familia, d).orElseThrow();
		assertThat(fila.get("id")).isEqualTo(respuesta.resultados().get(0).vinculo().vinculoId());
		assertThat(fila.get("escuela_id")).isEqualTo(escuelaA);
		assertThat(fila.get("estado")).isEqualTo("ACTIVO");
		assertThat(fila.get("es_principal")).isEqualTo(true);
		assertThat(fila.get("autorizado_por")).isEqualTo(adminId);
		assertThat(fila.get("autorizado_en")).isNotNull();
		assertThat(fila.get("creado_en")).isNotNull();
		assertThat(respuesta.resultados().get(0).vinculo().autorizadoEn()).isNotNull();
	}

	@Test
	void esPrincipalFalseSePersisteComoFalseYElDefaultTrueDeV1NuncaSeUsa() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");

		// Por la API: el segundo vinculo del mismo deportista no es principal.
		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.vincular(admin, f2, List.of(d), DATOS);
		// Directo por el repositorio: el booleano obligatorio del factory se escribe tal cual (INSERT con es_principal).
		UUID f3 = datos.familia(escuelaA, "Tres", true);
		repositorio.saveAndFlush(FamiliaDeportista.activo(escuelaA, f3, d, adminId, java.time.Instant.now(), false));

		assertThat(fila(f1, d).orElseThrow().get("es_principal")).isEqualTo(true);
		assertThat(fila(f2, d).orElseThrow().get("es_principal")).isEqualTo(false);
		assertThat(fila(f3, d).orElseThrow().get("es_principal")).isEqualTo(false);
		assertThat(principalesActivos(d)).isEqualTo(1);
	}

	@Test
	void elEstadoSeGuardaComoTextoDelEnumYSeLeeDeVuelta() {
		UUID familia = datos.familia(escuelaA, "Perez", true);
		for (EstadoVinculo estado : EstadoVinculo.values()) {
			UUID d = deportista(estado.name());
			datos.vinculo(escuelaA, familia, d, estado.name(), false, estado == EstadoVinculo.ACTIVO ? adminId : null);

			assertThat(repositorio.buscarVinculo(escuelaA, familia, d).orElseThrow().getEstado()).isEqualTo(estado);
		}
	}

	@Test
	void revincularReutilizaLaMismaFilaConSuIdYCreadoEnYRefrescaLaAutorizacion() throws Exception {
		UUID familia = datos.familia(escuelaA, "Perez", true);
		UUID d = deportista("Uno");
		UUID admin2 = datos.admin(escuelaA, "otro@a.example", true);
		UUID primero = servicio.vincular(admin, familia, List.of(d), DATOS).resultados().get(0).vinculo().vinculoId();
		Map<String, Object> antes = fila(familia, d).orElseThrow();
		servicio.revocar(admin, familia, d, DATOS);
		Thread.sleep(20);

		var relink = servicio.vincular(new com.banfieldpatin.backend.seguridad.UsuarioAutenticado(admin2, escuelaA, null,
				com.banfieldpatin.backend.usuarios.Rol.ADMIN), familia, List.of(d), DATOS);

		assertThat(relink.resultados().get(0).resultado()).isEqualTo(ResultadoVinculacion.REACTIVADO);
		assertThat(relink.resultados().get(0).vinculo().vinculoId()).isEqualTo(primero);
		assertThat(filas(d)).singleElement().satisfies(fila -> {
			assertThat(fila.get("id")).isEqualTo(primero);
			assertThat(fila.get("creado_en")).isEqualTo(antes.get("creado_en"));
			assertThat(fila.get("estado")).isEqualTo("ACTIVO");
			assertThat(fila.get("autorizado_por")).isEqualTo(admin2);
			assertThat(((java.sql.Timestamp) fila.get("autorizado_en")).toInstant())
					.isAfter(((java.sql.Timestamp) antes.get("autorizado_en")).toInstant());
			assertThat(fila.get("es_principal")).isEqualTo(true);
		});
	}

	@Test
	void todoVinculoActivoCreadoPorLaApiTieneAutorizadoPorYAutorizadoEnNoNulos() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d1 = deportista("Uno");
		UUID d2 = deportista("Dos");
		servicio.vincular(admin, f1, List.of(d1, d2), DATOS);
		servicio.vincular(admin, f2, List.of(d1), DATOS);
		servicio.revocar(admin, f1, d2, DATOS);
		servicio.vincular(admin, f1, List.of(d2), DATOS);

		assertThat(jdbc.sql("""
				SELECT count(*) FROM gestion_patin.familia_deportista
				WHERE escuela_id = :e AND estado = 'ACTIVO' AND autorizado_por = :a AND autorizado_en IS NOT NULL
				""").param("e", escuelaA).param("a", adminId).query(Long.class).single()).isEqualTo(3);
		verificarInvariantes();
	}

	@Test
	void unPendienteConElPrincipalPorDefectoDeV1NoCuentaYAlReactivarloSeRecalculaExplicitamente() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f1, List.of(d), DATOS);
		// Fila PENDIENTE con es_principal=true (lo que dejaria el DEFAULT de V1): no es un principal activo.
		datos.vinculo(escuelaA, f2, d, "PENDIENTE", true, null);
		assertThat(repositorio.principalesActivos(escuelaA, List.of(d))).containsExactly(d);

		var r = servicio.vincular(admin, f2, List.of(d), DATOS);

		assertThat(r.resultados().get(0).resultado()).isEqualTo(ResultadoVinculacion.REACTIVADO);
		assertThat(r.resultados().get(0).vinculo().esPrincipal()).isFalse();
		assertThat(fila(f2, d).orElseThrow().get("es_principal")).isEqualTo(false);
		assertThat(principalesActivos(d)).isEqualTo(1);
	}

	// ---------- literal de enum con nombre completo en el JPQL ----------

	@Test
	void losLiteralesDeEnumDeLasConsultasSoloCoincidenConActivoYElUpdateSoloTocaActivos() {
		UUID familia = datos.familia(escuelaA, "Perez", true);
		UUID activoPrincipal = deportista("A");
		UUID activoSecundario = deportista("B");
		UUID revocado = deportista("C");
		UUID pendiente = deportista("D");
		UUID rechazado = deportista("E");
		UUID inactivoConActivo = deportista(escuelaA, "F", false);
		datos.vinculo(escuelaA, familia, activoPrincipal, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, familia, activoSecundario, "ACTIVO", false, adminId);
		datos.vinculo(escuelaA, familia, revocado, "REVOCADO", false, adminId);
		datos.vinculo(escuelaA, familia, pendiente, "PENDIENTE", true, null);
		datos.vinculo(escuelaA, familia, rechazado, "RECHAZADO", true, null);
		datos.vinculo(escuelaA, familia, inactivoConActivo, "ACTIVO", false, adminId);
		List<UUID> todos = List.of(activoPrincipal, activoSecundario, revocado, pendiente, rechazado, inactivoConActivo);

		assertThat(repositorio.principalesActivos(escuelaA, todos)).containsExactly(activoPrincipal);
		assertThat(repositorio.principalActivo(escuelaA, activoPrincipal)).isPresent();
		assertThat(repositorio.principalActivo(escuelaA, pendiente)).isEmpty();
		// ACTIVO y deportista activo: 2 (el ACTIVO de un deportista inactivo no cuenta; los demas estados tampoco).
		assertThat(repositorio.contarActivosPorFamilia(escuelaA, List.of(familia))).singleElement()
				.satisfies(c -> assertThat(c.cantidad()).isEqualTo(2));

		assertThat(revocarSiActivo(familia, activoPrincipal)).isEqualTo(1);
		assertThat(revocarSiActivo(familia, activoPrincipal)).isZero();
		assertThat(revocarSiActivo(familia, pendiente)).isZero();
		assertThat(revocarSiActivo(familia, rechazado)).isZero();
		assertThat(fila(familia, activoPrincipal).orElseThrow()).containsEntry("estado", "REVOCADO")
				.containsEntry("es_principal", false);
		assertThat(fila(familia, pendiente).orElseThrow()).containsEntry("estado", "PENDIENTE");
		assertThat(fila(familia, rechazado).orElseThrow()).containsEntry("estado", "RECHAZADO");
	}

	private int revocarSiActivo(UUID familia, UUID deportista) {
		Integer filas = transaccion.execute(s -> repositorio.revocarSiActivo(escuelaA, familia, deportista));
		return filas;
	}

	@Test
	void lasProyeccionesTraenTodosLosEstadosConLosDatosJoinedYOrdenadosPorApellidoYPorNombreDeFamilia() {
		UUID fb = datos.familia(escuelaA, "Beta", true);
		UUID fa = datos.familia(escuelaA, "alfa", false);
		UUID d1 = datos.deportista(escuelaA, "50000001", "Zoe", "Zeta", true);
		UUID d2 = datos.deportista(escuelaA, "50000002", "Ana", "Alvarez", false);
		datos.vinculo(escuelaA, fb, d1, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fb, d2, "REVOCADO", false, adminId);
		datos.vinculo(escuelaA, fa, d1, "PENDIENTE", false, null);

		List<VinculoRespuesta> deFamilia = repositorio.deFamilia(escuelaA, fb);
		List<VinculoRespuesta> deDeportista = repositorio.deDeportista(escuelaA, d1);

		assertThat(deFamilia).extracting(VinculoRespuesta::deportistaApellido).containsExactly("Alvarez", "Zeta");
		assertThat(deFamilia).extracting(VinculoRespuesta::estado).containsExactly(EstadoVinculo.REVOCADO,
				EstadoVinculo.ACTIVO);
		assertThat(deFamilia.get(0).deportistaActivo()).isFalse();
		assertThat(deFamilia.get(0).deportistaNombre()).isEqualTo("Ana");
		assertThat(deFamilia.get(1)).satisfies(v -> {
			assertThat(v.esPrincipal()).isTrue();
			assertThat(v.familiaNombre()).isEqualTo("Beta");
			assertThat(v.autorizadoEn()).isNotNull();
		});
		// Por familia: "alfa" (PENDIENTE, sin autorizacion) antes que "Beta" (orden sin distinguir mayusculas).
		assertThat(deDeportista).extracting(VinculoRespuesta::familiaNombre).containsExactly("alfa", "Beta");
		assertThat(deDeportista.get(0).estado()).isEqualTo(EstadoVinculo.PENDIENTE);
		assertThat(deDeportista.get(0).autorizadoEn()).isNull();
		assertThat(repositorio.respuesta(escuelaA, fb, d1)).isPresent();
		assertThat(repositorio.respuesta(escuelaB, fb, d1)).isEmpty();
	}
}
