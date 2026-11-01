package com.banfieldpatin.backend.temporadas.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.banfieldpatin.backend.temporadas.EstadoDeportivo;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/** Normalizacion y validacion AL GUARDAR de los textos deportivos (no se espera a la exportacion de la LBF). */
class DeportistaTemporadaSolicitudTest {

	private static final Validator VALIDADOR = Validation.buildDefaultValidatorFactory().getValidator();
	private static final UUID TEMPORADA = UUID.randomUUID();

	private static Set<String> camposInvalidos(Object solicitud) {
		return VALIDADOR.validate(solicitud).stream().map(v -> v.getPropertyPath().toString())
				.collect(java.util.stream.Collectors.toSet());
	}

	private static DeportistaTemporadaSolicitud alta(String categoria, String rama, String divisional, String numero) {
		return new DeportistaTemporadaSolicitud(TEMPORADA, categoria, rama, divisional, numero, null);
	}

	@Test
	void recortaColapsaEspaciosInternosYPasaLoVacioANull() {
		DeportistaTemporadaSolicitud s = alta("  Sub   12  ", "   ", "\t", "  ");

		assertThat(s.categoria()).isEqualTo("Sub 12");
		assertThat(s.rama()).isNull();
		assertThat(s.divisional()).isNull();
		assertThat(s.numeroCorredor()).isNull();
		assertThat(camposInvalidos(s)).isEmpty();
	}

	@Test
	void elNumeroDeCorredorEsTextoOpacoQueConservaCerosInicialesYEspaciosInternos() {
		assertThat(alta(null, null, null, " 007 ").numeroCorredor()).isEqualTo("007");
		assertThat(alta(null, null, null, "A  12").numeroCorredor()).isEqualTo("A  12");
	}

	@Test
	void elEstadoPorDefectoDelAltaEsActivoYUnoExplicitoSeRespeta() {
		assertThat(alta(null, null, null, null).estadoDeportivo()).isEqualTo(EstadoDeportivo.ACTIVO);
		assertThat(new DeportistaTemporadaSolicitud(TEMPORADA, null, null, null, null, EstadoDeportivo.SUSPENDIDO)
				.estadoDeportivo()).isEqualTo(EstadoDeportivo.SUSPENDIDO);
	}

	@Test
	void ningunDatoDeportivoEsObligatorioPeroLaTemporadaSi() {
		assertThat(camposInvalidos(alta(null, null, null, null))).isEmpty();
		assertThat(camposInvalidos(new DeportistaTemporadaSolicitud(null, null, null, null, null, null)))
				.containsExactly("temporadaId");
	}

	@Test
	void rechazaLosLargosQueLaBaseNoAdmiteYAceptaLosLimites() {
		assertThat(camposInvalidos(alta("c".repeat(81), "r".repeat(41), "d".repeat(81), "n".repeat(31))))
				.containsExactlyInAnyOrder("categoria", "rama", "divisional", "numeroCorredor");
		assertThat(camposInvalidos(alta("c".repeat(80), "r".repeat(40), "d".repeat(80), "n".repeat(30)))).isEmpty();
	}

	@Test
	void rechazaCaracteresDeControlPeroLosSaltosYTabulacionesInternosDeLosTextosSeNormalizanAEspacios() {
		assertThat(alta("Sub\n12", null, null, null).categoria()).isEqualTo("Sub 12");
		assertThat(camposInvalidos(alta("Sub\u0000", "Fem\u0007", null, "1\u0000"))).containsExactlyInAnyOrder("categoria",
				"rama", "numeroCorredor");
		// El numero conserva los espacios internos, pero un salto de linea o una tabulacion es un caracter de control.
		assertThat(camposInvalidos(alta(null, null, null, "1\n2"))).containsExactly("numeroCorredor");
		assertThat(camposInvalidos(alta(null, null, null, "1\t2"))).containsExactly("numeroCorredor");
	}

	@Test
	void laActualizacionExigeElEstadoYNormalizaIgual() {
		DeportistaTemporadaActualizacion sinEstado = new DeportistaTemporadaActualizacion(" Sub  14 ", null, null, " 08 ", null);

		assertThat(sinEstado.categoria()).isEqualTo("Sub 14");
		assertThat(sinEstado.numeroCorredor()).isEqualTo("08");
		assertThat(camposInvalidos(sinEstado)).containsExactly("estadoDeportivo");
		assertThat(camposInvalidos(new DeportistaTemporadaActualizacion(null, null, null, null, EstadoDeportivo.INACTIVO)))
				.isEmpty();
	}
}
