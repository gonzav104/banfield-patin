package com.banfieldpatin.backend.familias.dto;

import java.util.UUID;

import com.banfieldpatin.backend.familias.Familia;

/** Fila del listado administrativo; las cantidades se calculan con consultas agrupadas (sin N+1). */
public record FamiliaAdminResumen(UUID id, String nombreReferencia, boolean activa, long cantidadTutores,
		long cantidadDeportistasActivos) {

	/** Los conteos son 0 hasta que existan tutores (cantidadTutores) y vinculos (cantidadDeportistasActivos). */
	public static FamiliaAdminResumen de(Familia f, long cantidadTutores, long cantidadDeportistasActivos) {
		return new FamiliaAdminResumen(f.getId(), f.getNombreReferencia(), f.isActiva(), cantidadTutores,
				cantidadDeportistasActivos);
	}
}
