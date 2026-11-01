package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.temporadas.dto.TemporadaRespuesta;
import com.banfieldpatin.backend.temporadas.dto.TemporadaSolicitud;

/** Reglas de temporadas contra PostgreSQL real: alta, lectura, aislamiento por escuela, activacion y auditoria. */
@PruebaDb
@Import(ConfigTemporadasDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=5",
		"spring.datasource.hikari.minimum-idle=1" })
class TemporadaServiceDbTest extends BaseTemporadasDb {

	private static void assertError(Throwable e, HttpStatus estado, String codigo) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, n -> {
			assertThat(n.getEstado()).isEqualTo(estado);
			assertThat(n.getCodigo()).isEqualTo(codigo);
		});
	}

	// ---------- alta ----------

	@Test
	void unaTemporadaNuevaNaceInactivaYDejaUnaAuditoriaConElAnio() {
		TemporadaRespuesta creada = servicio.crear(admin, new TemporadaSolicitud(2027), DATOS);

		assertThat(creada.anio()).isEqualTo(2027);
		assertThat(creada.activa()).isFalse();
		assertThat(activa(creada.id())).isFalse();
		Map<String, Object> evento = jdbc.sql("""
				SELECT accion, recurso_tipo, recurso_id, usuario_id, detalle->>'anio' AS anio, host(ip) AS ip, user_agent
				FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'TEMPORADA_CREADA'
				""").param("e", escuelaA).query().singleRow();
		assertThat(evento).containsEntry("recurso_tipo", "TEMPORADA").containsEntry("recurso_id", creada.id())
				.containsEntry("usuario_id", admin.id()).containsEntry("anio", "2027").containsEntry("ip", "10.0.0.9")
				.containsEntry("user_agent", "JUnit");
	}

	@Test
	void elMismoAnioEnLaMismaEscuelaDa409SinSegundaFilaNiAuditoriaPeroEnOtraEscuelaSePuede() {
		servicio.crear(admin, new TemporadaSolicitud(2027), DATOS);

		assertThatThrownBy(() -> servicio.crear(admin, new TemporadaSolicitud(2027), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "TEMPORADA_ANIO_DUPLICADO"));
		servicio.crear(adminDeB, new TemporadaSolicitud(2027), DATOS);

		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.temporada WHERE escuela_id = :e").param("e", escuelaA)
				.query(Long.class).single()).isEqualTo(1);
		assertThat(contarAuditoria(escuelaA, "TEMPORADA_CREADA")).isEqualTo(1);
		assertThat(contarAuditoria(escuelaB, "TEMPORADA_CREADA")).isEqualTo(1);
	}

	// ---------- lectura ----------

	@Test
	void elListadoVaDelAnioMasNuevoAlMasViejoYSoloMuestraLaEscuelaDelAdmin() {
		datos.temporada(escuelaA, 2026, true);
		datos.temporada(escuelaA, 2028, false);
		datos.temporada(escuelaA, 2027, false);
		datos.temporada(escuelaB, 2030, false);

		Pagina<TemporadaRespuesta> pagina = servicio.listar(admin, Pagina.pedir(0, 2));

		assertThat(pagina.totalElementos()).isEqualTo(3);
		assertThat(pagina.contenido()).extracting(TemporadaRespuesta::anio).containsExactly(2028, 2027);
		assertThat(servicio.listar(admin, Pagina.pedir(1, 2)).contenido()).extracting(TemporadaRespuesta::anio)
				.containsExactly(2026);
	}

	@Test
	void unaTemporadaDeOtraEscuelaEsIndistinguibleDeUnaInexistente() {
		UUID deB = datos.temporada(escuelaB, 2027, false);

		assertThatThrownBy(() -> servicio.obtener(admin, deB))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "TEMPORADA_NO_ENCONTRADA"));
		assertThatThrownBy(() -> servicio.obtener(admin, UUID.randomUUID()))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "TEMPORADA_NO_ENCONTRADA"));
		assertThatThrownBy(() -> servicio.activar(admin, deB, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "TEMPORADA_NO_ENCONTRADA"));
		assertThat(activa(deB)).isFalse();
		assertThat(contarAuditoria(escuelaB, "TEMPORADA_ACTIVADA")).isZero();
	}

	// ---------- activacion ----------

	@Test
	void activarDesactivaLaAnteriorDejaUnaSolaActivaYAuditaConLaAnterior() {
		UUID vieja = datos.temporada(escuelaA, 2026, true);
		UUID nueva = datos.temporada(escuelaA, 2027, false);

		TemporadaRespuesta respuesta = servicio.activar(admin, nueva, DATOS);

		assertThat(respuesta).isEqualTo(new TemporadaRespuesta(nueva, 2027, true));
		assertThat(activasDe(escuelaA)).containsExactly(nueva);
		assertThat(activa(vieja)).isFalse();
		Map<String, Object> evento = jdbc.sql("""
				SELECT recurso_id, detalle->>'anio' AS anio, detalle->>'anteriorTemporadaId' AS anterior
				FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'TEMPORADA_ACTIVADA'
				""").param("e", escuelaA).query().singleRow();
		assertThat(evento).containsEntry("recurso_id", nueva).containsEntry("anio", "2027")
				.containsEntry("anterior", vieja.toString());
	}

	@Test
	void sinTemporadaActivaPreviaActivarFuncionaYLaAnteriorQuedaNulaEnLaAuditoria() {
		UUID unica = datos.temporada(escuelaA, 2027, false);

		servicio.activar(admin, unica, DATOS);

		assertThat(activasDe(escuelaA)).containsExactly(unica);
		assertThat(jdbc.sql("""
				SELECT detalle->'anteriorTemporadaId' = 'null'::jsonb FROM gestion_patin.auditoria
				WHERE escuela_id = :e AND accion = 'TEMPORADA_ACTIVADA'
				""").param("e", escuelaA).query(Boolean.class).single()).isTrue();
	}

	@Test
	void activarLaYaActivaEsIdempotenteSinEscribirNiAuditar() {
		UUID activa = datos.temporada(escuelaA, 2027, true);
		Object antes = actualizadoEn(activa);

		TemporadaRespuesta respuesta = servicio.activar(admin, activa, DATOS);

		assertThat(respuesta).isEqualTo(new TemporadaRespuesta(activa, 2027, true));
		assertThat(actualizadoEn(activa)).as("sin UPDATE: el trigger no toco actualizado_en").isEqualTo(antes);
		assertThat(contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA")).isZero();
	}

	@Test
	void activarEnUnaEscuelaNoTocaLasTemporadasDeOtra() {
		UUID activaDeB = datos.temporada(escuelaB, 2026, true);
		UUID deA = datos.temporada(escuelaA, 2027, false);

		servicio.activar(admin, deA, DATOS);

		assertThat(activasDe(escuelaB)).containsExactly(activaDeB);
		assertThat(activasDe(escuelaA)).containsExactly(deA);
	}

	// ---------- red de seguridad de la base (V1) ----------

	@Test
	void laBaseRechazaDosActivasPorEscuelaUnAnioRepetidoYUnAnioFueraDeRango() {
		datos.temporada(escuelaA, 2026, true);
		datos.temporada(escuelaA, 2027, false);

		assertThatThrownBy(() -> datos.temporada(escuelaA, 2028, true)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_temporada_activa_por_escuela");
		assertThatThrownBy(() -> datos.temporada(escuelaA, 2027, false)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_temporada_escuela_anio");
		assertThatThrownBy(() -> datos.temporada(escuelaA, 2019, false)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> datos.temporada(escuelaA, 2101, false)).isInstanceOf(DataIntegrityViolationException.class);
		// Dos escuelas SI pueden tener cada una su activa.
		datos.temporada(escuelaB, 2026, true);
		assertThat(List.of(activasDe(escuelaA), activasDe(escuelaB))).allSatisfy(l -> assertThat(l).hasSize(1));
	}
}
