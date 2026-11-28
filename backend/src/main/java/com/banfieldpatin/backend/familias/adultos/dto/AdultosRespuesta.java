package com.banfieldpatin.backend.familias.adultos.dto;

import java.util.List;

/**
 * Adultos activos de la propia familia. {@code habilitado} dice si la familia puede cargar o editar desde acá (bandera);
 * {@code tope} y {@code cargadosPorLaFamilia} permiten avisar antes de llegar al límite (los que cargó el club no cuentan).
 */
public record AdultosRespuesta(boolean habilitado, int tope, long cargadosPorLaFamilia, List<AdultoDeFamilia> adultos) {
}
