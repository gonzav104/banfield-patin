package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.temporadas.dto.TemporadaRespuesta;

/**
 * La espera del bloqueo de la escuela esta acotada ({@code banfield.temporadas.lock-timeout}, aqui 0,3 s): una activacion que
 * no obtiene el bloqueo a tiempo responde 409 CONFLICTO_CONCURRENCIA, no deja nada escrito ni auditado, y el limite no se filtra
 * a otras transacciones (SET LOCAL).
 */
@PruebaDb
@Import(ConfigTemporadasDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=5",
		"spring.datasource.hikari.minimum-idle=1", "banfield.temporadas.lock-timeout=PT0.3S" })
class TemporadasLockTimeoutDbTest extends BaseTemporadasDb {

	@Test
	void unaActivacionQueNoObtieneElBloqueoATiempoDa409SinEscribirNiAuditarYElLimiteNoSeFiltra() throws Exception {
		UUID t1 = datos.temporada(escuelaA, 2026, false);
		UUID t2 = datos.temporada(escuelaA, 2027, false);

		try (Retencion a = new Retencion(() -> servicio.activar(admin, t1, DATOS))) {
			Future<Object> b = enOtroHilo(() -> servicio.activar(admin, t2, DATOS));

			assertThat(b.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
				assertThat(e.getEstado()).isEqualTo(HttpStatus.CONFLICT);
				assertThat(e.getCodigo()).isEqualTo("CONFLICTO_CONCURRENCIA");
				assertThat(e.getMessage()).doesNotContain("deportista");
			});
			assertThat(a.confirmar()).isEqualTo(new TemporadaRespuesta(t1, 2026, true));
		}

		assertThat(activasDe(escuelaA)).containsExactly(t1);
		assertThat(activa(t2)).isFalse();
		assertThat(contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA")).isEqualTo(1);
		// Nunca fuera de la transaccion: PostgreSQL vuelve a su valor por defecto (0 = sin limite) en toda conexion del pool.
		for (int i = 0; i < 5; i++) {
			assertThat(jdbc.sql("SHOW lock_timeout").query(String.class).single()).isEqualTo("0");
		}
	}

	@org.springframework.beans.factory.annotation.Autowired
	com.banfieldpatin.backend.temporadas.DeportistaTemporadaAdminService dts;

	@Test
	void unaEdicionDeDatosDeportivosQueNoObtieneElBloqueoDeLaFilaATiempoDa409SinEscribirNiAuditar() throws Exception {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		dts.crear(admin, d, new com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaSolicitud(t, "Sub 12", null,
				null, null, null), DATOS);
		var cambioA = new com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaActualizacion("Sub 14", null, null, null,
				com.banfieldpatin.backend.temporadas.EstadoDeportivo.ACTIVO);
		var cambioB = new com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaActualizacion("Sub 16", null, null, null,
				com.banfieldpatin.backend.temporadas.EstadoDeportivo.ACTIVO);

		try (Retencion a = new Retencion(() -> dts.actualizar(admin, d, t, cambioA, DATOS))) {
			Future<Object> b = enOtroHilo(() -> dts.actualizar(admin, d, t, cambioB, DATOS));

			assertThat(b.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
				assertThat(e.getEstado()).isEqualTo(HttpStatus.CONFLICT);
				assertThat(e.getCodigo()).isEqualTo("CONFLICTO_CONCURRENCIA");
				assertThat(e.getMessage()).contains("datos deportivos").doesNotContain("activacion");
			});
			a.confirmar();
		}

		assertThat(dts.obtener(admin, d, t).categoria()).isEqualTo("Sub 14");
		assertThat(contarAuditoria(escuelaA, "DEPORTISTA_TEMPORADA_ACTUALIZADA")).isEqualTo(1);
		for (int i = 0; i < 5; i++) {
			assertThat(jdbc.sql("SHOW lock_timeout").query(String.class).single()).isEqualTo("0");
		}
	}
}

