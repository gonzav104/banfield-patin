package com.banfieldpatin.backend.familias.tutores.verificacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.banfieldpatin.backend.familias.tutores.verificacion.dto.LoteVerificacion;
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.ResultadoLote;

/** El lote nunca deja a un tutor sin resultado: una falla inesperada es ERROR y, vencido el plazo, el resto es NO_PROCESADO. */
class LoteVerificacionTest {

	private final Instant ahora = Instant.parse("2026-11-28T10:00:00Z");

	@Test
	void unaFallaInesperadaDeUnTutorNoAbortaElLoteYSigueConLosDemas() {
		List<LoteVerificacion.Item> items = List.of(item(), item(), item());
		List<ResultadoLote.Item> r = VerificacionTutoresService.procesar(items, i -> {
			if (i == items.get(1)) {
				throw new IllegalStateException("violación inesperada");
			}
			return new ResultadoLote.Item(i.tutorId(), "VERIFICADO", "1");
		}, Clock.fixed(ahora, ZoneOffset.UTC), ahora.plusSeconds(15));
		assertThat(r).extracting(ResultadoLote.Item::resultado).containsExactly("VERIFICADO", "ERROR", "VERIFICADO");
		assertThat(r).extracting(ResultadoLote.Item::tutorId).containsExactlyElementsOf(items.stream().map(LoteVerificacion.Item::tutorId).toList());
	}

	@Test
	void vencidoElPlazoLosQueFaltanVuelvenNoProcesadosSinIntentarlos() {
		List<LoteVerificacion.Item> items = List.of(item(), item(), item());
		int[] intentos = { 0 };
		Clock vencido = Clock.fixed(ahora.plusSeconds(15), ZoneOffset.UTC);
		List<ResultadoLote.Item> r = VerificacionTutoresService.procesar(items, i -> {
			intentos[0]++;
			return new ResultadoLote.Item(i.tutorId(), "VERIFICADO", "1");
		}, vencido, ahora.plusSeconds(15));
		assertThat(r).extracting(ResultadoLote.Item::resultado).containsOnly("NO_PROCESADO");
		assertThat(intentos[0]).isZero();
	}

	private static LoteVerificacion.Item item() {
		return new LoteVerificacion.Item(UUID.randomUUID(), 0L);
	}
}
