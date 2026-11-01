package com.banfieldpatin.backend.familias.vinculos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * El alcance de la FAMILIA es una GUARDA DE SEGURIDAD: el WHERE de las consultas del portal es la autorizacion. Se parte en
 * dos tramos (FROM/JOIN y WHERE) para poder agregar mas JOIN antes del WHERE sin duplicar el texto. Estas pruebas demuestran
 * que el texto resultante es EXACTAMENTE el anterior a la separacion (copiado aqui como literal del commit base) y que las
 * tres consultas que ya lo usan no cambian.
 */
class AlcanceFamiliaTest {

	/** El texto de {@code ALCANCE_FAMILIA} antes de partirlo, caracter por caracter. */
	private static final String TEXTO_ORIGINAL = " from FamiliaDeportista fd\n"
			+ "join Familia f on f.id = fd.familiaId and f.escuelaId = fd.escuelaId\n"
			+ "join Deportista d on d.id = fd.deportistaId and d.escuelaId = fd.escuelaId\n"
			+ "where fd.familiaId = :familiaId and fd.escuelaId = :escuelaId\n"
			+ "  and fd.estado = com.banfieldpatin.backend.familias.vinculos.EstadoVinculo.ACTIVO\n"
			+ "  and f.activa = true\n";

	@Test
	void elAlcanceCompletoSigueSiendoExactamenteElTextoOriginal() {
		assertThat(FamiliaDeportistaRepository.ALCANCE_FAMILIA).isEqualTo(TEXTO_ORIGINAL);
	}

	@Test
	void losDosTramosConcatenadosSonElAlcanceCompleto() {
		assertThat(FamiliaDeportistaRepository.ALCANCE_FAMILIA_JOINS + FamiliaDeportistaRepository.ALCANCE_FAMILIA_FILTRO)
				.isEqualTo(FamiliaDeportistaRepository.ALCANCE_FAMILIA);
	}

	@Test
	void elTramoDeJoinsNoTieneWhereYElDeFiltroEmpiezaConElYContieneTodosLosPredicadosDeSeguridad() {
		assertThat(FamiliaDeportistaRepository.ALCANCE_FAMILIA_JOINS).startsWith(" from FamiliaDeportista fd")
				.doesNotContain("where").doesNotContain(":familiaId");
		assertThat(FamiliaDeportistaRepository.ALCANCE_FAMILIA_FILTRO).startsWith("where ")
				.contains("fd.familiaId = :familiaId", "fd.escuelaId = :escuelaId", "EstadoVinculo.ACTIVO", "f.activa = true")
				// Un deportista inactivo con vinculo ACTIVO sigue visible: no hay predicado sobre d.activo.
				.doesNotContain("d.activo");
	}
}
