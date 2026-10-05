package com.banfieldpatin.backend.familias.vinculos.dto;

import java.util.List;

/** Resultado del lote de vinculacion, en el mismo orden de la solicitud (sin duplicados). */
public record VinculacionRespuesta(List<Resultado> resultados) {

	public VinculacionRespuesta {
		resultados = List.copyOf(resultados);
	}

	public record Resultado(VinculoRespuesta vinculo, ResultadoVinculacion resultado) {
	}
}
