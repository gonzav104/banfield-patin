package com.banfieldpatin.backend.familias.portal.dto;

import com.banfieldpatin.backend.temporadas.EstadoDeportivo;

/**
 * Datos deportivos de la temporada VIGENTE dentro de la ficha de FAMILIA, solo lectura. Muestra exclusivamente el anio y los
 * cinco datos deportivos acordados; NO el id de la temporada ni de la fila, ni {@code temporadaActiva} (siempre verdadero
 * aqui), ni {@code escuelaId}, ni marcas de tiempo. Texto libre sin catalogo oficial: puede ser {@code null}.
 */
public record TemporadaVigenteDeFicha(Short anio, String categoria, String rama, String divisional, String numeroCorredor,
		EstadoDeportivo estadoDeportivo) {

	/** Descarta lo que la ficha no muestra (id de la temporada y bandera de vigente). */
	public static TemporadaVigenteDeFicha de(TemporadaDeFamilia temporada) {
		return new TemporadaVigenteDeFicha(temporada.anio(), temporada.categoria(), temporada.rama(), temporada.divisional(),
				temporada.numeroCorredor(), temporada.estadoDeportivo());
	}

	@Override
	public String toString() {
		return "TemporadaVigenteDeFicha[anio=" + anio + "]";
	}
}
