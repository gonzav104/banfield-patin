package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.temporadas.dto.TemporadaRespuesta;

/**
 * FE-05/U13: la activacion lleva la intencion del ADMIN ({@code activaEsperadaId}: la temporada activa que observo, o
 * null si no habia ninguna) y se compara DESPUES de tomar el bloqueo de la escuela, contra PostgreSQL real. Una decision
 * tomada sobre un estado desactualizado se rechaza con 409 EDICION_DESACTUALIZADA, sin escrituras parciales ni auditoria
 * y sin reintentos automaticos. Las anotaciones de contexto son identicas a las de TemporadasConcurrenciaDbTest para
 * compartir el contexto cacheado (el contenedor tiene max_connections acotado).
 */
@PruebaDb
@Import(ConfigTemporadasDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=5",
		"spring.datasource.hikari.minimum-idle=1" })
class TemporadasIntencionDbTest extends BaseTemporadasDb {

	@AfterEach
	void limpiarBase() {
		hilos.shutdownNow();
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private static void assertDesactualizada(Throwable e) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, n -> {
			assertThat(n.getEstado()).isEqualTo(HttpStatus.CONFLICT);
			assertThat(n.getCodigo()).isEqualTo("EDICION_DESACTUALIZADA");
		});
	}

	@Test
	void unaConfirmacionBasadaEnUnEstadoViejoSeRechazaSinEscribirNiAuditarYDejaIntactaLaActivaVigente() {
		// El admin 1 observo A activa; el admin 2 activa B; el admin 1 confirma C creyendo que sigue A.
		UUID a = datos.temporada(escuelaA, 2026, true);
		UUID b = datos.temporada(escuelaA, 2027, false);
		UUID c = datos.temporada(escuelaA, 2028, false);
		servicio.activar(admin, b, a, DATOS);
		Object cAntes = actualizadoEn(c);
		Object bAntes = actualizadoEn(b);

		assertDesactualizada(catchThrowable(() -> servicio.activar(admin, c, a, DATOS)));

		assertThat(activasDe(escuelaA)).containsExactly(b);
		assertThat(activa(c)).isFalse();
		assertThat(actualizadoEn(c)).as("C no se toco").isEqualTo(cAntes);
		assertThat(actualizadoEn(b)).as("B no se toco").isEqualTo(bAntes);
		assertThat(contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA")).as("solo la de B").isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND recurso_id = :r")
				.param("e", escuelaA).param("r", c).query(Long.class).single()).isZero();
	}

	@Test
	void trasElRechazoElAdminRecargaYConfirmaDeNuevoConLaIntencionVigenteYEntoncesFunciona() {
		UUID a = datos.temporada(escuelaA, 2026, true);
		UUID b = datos.temporada(escuelaA, 2027, false);
		UUID c = datos.temporada(escuelaA, 2028, false);
		servicio.activar(admin, b, a, DATOS);
		assertDesactualizada(catchThrowable(() -> servicio.activar(admin, c, a, DATOS)));

		// Confirmacion explicita y nueva (no un reintento automatico): ahora observa que la activa es B.
		TemporadaRespuesta respuesta = servicio.activar(admin, c, b, DATOS);

		assertThat(respuesta).isEqualTo(new TemporadaRespuesta(c, 2028, true));
		assertThat(activasDe(escuelaA)).containsExactly(c);
		assertThat(jdbc.sql("""
				SELECT detalle->>'anteriorTemporadaId' FROM gestion_patin.auditoria
				WHERE escuela_id = :e AND accion = 'TEMPORADA_ACTIVADA' AND recurso_id = :r
				""").param("e", escuelaA).param("r", c).query(String.class).single()).isEqualTo(b.toString());
	}

	@Test
	void dosAdminsConLaMismaObservacionLaSegundaActivacionEsperaElBloqueoYAlLiberarseRecibe409() throws Exception {
		UUID a = datos.temporada(escuelaA, 2026, true);
		UUID b = datos.temporada(escuelaA, 2027, false);
		UUID c = datos.temporada(escuelaA, 2028, false);

		Retencion primera = new Retencion(() -> servicio.activar(admin, b, a, DATOS));
		Future<Object> segunda = enOtroHilo(() -> servicio.activar(admin, c, a, DATOS));
		try {
			assertBloqueada(segunda);
		} finally {
			primera.close();
		}

		assertThat(primera.confirmar()).isEqualTo(new TemporadaRespuesta(b, 2027, true));
		assertDesactualizada((Throwable) segunda.get(15, TimeUnit.SECONDS));
		assertThat(activasDe(escuelaA)).containsExactly(b);
		assertThat(activa(c)).isFalse();
		assertThat(contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA")).isEqualTo(1);
	}

	@Test
	void laIntencionDesactualizadaNoImportaSiLaTemporadaPedidaYaEstaActivaEsIdempotente() {
		UUID a = datos.temporada(escuelaA, 2026, false);
		UUID otra = datos.temporada(escuelaA, 2027, false);
		servicio.activar(admin, a, null, DATOS);
		Object antes = actualizadoEn(a);
		long auditoria = contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA");

		// Estado observado viejo (ninguna activa / otra distinta): el resultado pedido ya es el vigente, no se pisa nada.
		assertThat(servicio.activar(admin, a, null, DATOS)).isEqualTo(new TemporadaRespuesta(a, 2026, true));
		assertThat(servicio.activar(admin, a, otra, DATOS)).isEqualTo(new TemporadaRespuesta(a, 2026, true));

		assertThat(actualizadoEn(a)).as("sin UPDATE").isEqualTo(antes);
		assertThat(contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA")).isEqualTo(auditoria);
		assertThat(activasDe(escuelaA)).containsExactly(a);
	}

	@Test
	void sinTemporadaActivaLaIntencionNulaFuncionaYUnaIntencionConIdDa409() {
		UUID t = datos.temporada(escuelaA, 2027, false);

		assertDesactualizada(catchThrowable(() -> servicio.activar(admin, t, UUID.randomUUID(), DATOS)));
		assertThat(activasDe(escuelaA)).isEmpty();
		assertThat(contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA")).isZero();

		assertThat(servicio.activar(admin, t, null, DATOS)).isEqualTo(new TemporadaRespuesta(t, 2027, true));
		assertThat(activasDe(escuelaA)).containsExactly(t);
	}

	@Test
	void esperarNingunaActivaCuandoYaHayUnaDa409YNoDesactivaNada() {
		UUID vigente = datos.temporada(escuelaA, 2026, true);
		UUID t = datos.temporada(escuelaA, 2027, false);

		assertDesactualizada(catchThrowable(() -> servicio.activar(admin, t, null, DATOS)));

		assertThat(activasDe(escuelaA)).containsExactly(vigente);
		assertThat(contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA")).isZero();
	}

	@Test
	void laIntencionSeComparaSoloContraLaEscuelaDelAdminYUnIdAjenoNoSeDistingueDeUnoInexistente() {
		UUID activaDeA = datos.temporada(escuelaA, 2026, true);
		UUID activaDeB = datos.temporada(escuelaB, 2026, true);
		UUID nuevaDeA = datos.temporada(escuelaA, 2027, false);
		UUID nuevaDeB = datos.temporada(escuelaB, 2027, false);

		// La intencion que nombra la activa de OTRA escuela es indistinguible de un id inexistente.
		Throwable conAjeno = catchThrowable(() -> servicio.activar(admin, nuevaDeA, activaDeB, DATOS));
		Throwable conInexistente = catchThrowable(() -> servicio.activar(admin, nuevaDeA, UUID.randomUUID(), DATOS));
		assertDesactualizada(conAjeno);
		assertDesactualizada(conInexistente);
		assertThat(conAjeno.getMessage()).isEqualTo(conInexistente.getMessage());
		// Una temporada de otra escuela sigue siendo 404 con cualquier intencion.
		assertThat(catchThrowable(() -> servicio.activar(admin, nuevaDeB, activaDeB, DATOS)))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, n -> assertThat(n.getCodigo()).isEqualTo("TEMPORADA_NO_ENCONTRADA"));

		assertThat(activasDe(escuelaA)).containsExactly(activaDeA);
		assertThat(activasDe(escuelaB)).containsExactly(activaDeB);
		assertThat(contarAuditoria(escuelaA, "TEMPORADA_ACTIVADA")).isZero();
		assertThat(contarAuditoria(escuelaB, "TEMPORADA_ACTIVADA")).isZero();
	}

	@Test
	void activacionesConIntencionEnDosEscuelasSonIndependientes() {
		UUID activaDeA = datos.temporada(escuelaA, 2026, true);
		UUID activaDeB = datos.temporada(escuelaB, 2026, true);
		UUID nuevaDeA = datos.temporada(escuelaA, 2027, false);
		UUID nuevaDeB = datos.temporada(escuelaB, 2027, false);

		servicio.activar(admin, nuevaDeA, activaDeA, DATOS);
		servicio.activar(adminDeB, nuevaDeB, activaDeB, DATOS);

		assertThat(activasDe(escuelaA)).containsExactly(nuevaDeA);
		assertThat(activasDe(escuelaB)).containsExactly(nuevaDeB);
	}

	private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable accion) {
		return org.assertj.core.api.Assertions.catchThrowable(accion);
	}
}
