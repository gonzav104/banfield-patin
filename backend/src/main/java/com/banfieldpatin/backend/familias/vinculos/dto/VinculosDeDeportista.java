package com.banfieldpatin.backend.familias.vinculos.dto;

import java.util.List;
import java.util.UUID;

/**
 * Revision de los vinculos de un deportista y sus vinculos (todos los estados), leidos de la MISMA instantanea. La
 * revision existe aunque no haya ningun vinculo. Cadena decimal, como las demas revisiones.
 */
public record VinculosDeDeportista(UUID deportistaId, String versionVinculos, List<VinculoRespuesta> vinculos) {

	public VinculosDeDeportista {
		vinculos = List.copyOf(vinculos);
	}
}
