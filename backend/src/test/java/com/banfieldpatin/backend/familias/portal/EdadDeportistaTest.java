package com.banfieldpatin.backend.familias.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * La edad de las respuestas del portal es una regla unica. La misma tabla de casos la usaba el cliente de FE-03 (age.test.ts)
 * antes de que la regla pasara al servidor: no se pierde cobertura al moverla. Mas el borde de la zona horaria de la escuela.
 */
class EdadDeportistaTest {

	@ParameterizedTest(name = "nacido {0}, hoy {1} -> {2}")
	@CsvSource({
			// cumpleanios hoy, vispera y dia siguiente
			"2018-11-13, 2026-11-13, 8", "2018-11-14, 2026-11-13, 7", "2018-11-12, 2026-11-13, 8",
			// recien nacidos
			"2026-11-13, 2026-11-13, 0", "2026-11-12, 2026-11-13, 0",
			// cambio de anio
			"2016-12-31, 2026-01-01, 9", "2016-12-31, 2026-12-30, 9", "2016-12-31, 2026-12-31, 10",
			// 29 de febrero: cumple el 1 de marzo en los anios sin 29 de febrero
			"2016-02-29, 2026-02-28, 9", "2016-02-29, 2026-03-01, 10", "2016-02-29, 2028-02-28, 11",
			"2016-02-29, 2028-02-29, 12", "2016-02-28, 2028-02-28, 12", "2000-02-29, 2025-02-28, 24",
			"2000-02-29, 2025-03-01, 25",
			// limite de edad: 120 se muestra, 121 es un error de carga
			"1906-11-13, 2026-11-13, 120", "1906-11-14, 2026-11-13, 119" })
	void cuentaLosAniosCumplidos(LocalDate nacimiento, LocalDate hoy, int esperada) {
		assertThat(EdadDeportista.calcular(nacimiento, hoy)).isEqualTo(esperada);
	}

	@ParameterizedTest(name = "nacido {0}, hoy {1} -> null")
	@CsvSource({ "2027-01-01, 2026-11-13", "2026-11-14, 2026-11-13", "1905-11-13, 2026-11-13", "1900-01-01, 2026-11-13" })
	void sinDatoFiableNoSeInventa(LocalDate nacimiento, LocalDate hoy) {
		assertThat(EdadDeportista.calcular(nacimiento, hoy)).isNull();
	}

	@Test
	void sinFechaDeNacimientoLaEdadEsNula() {
		assertThat(EdadDeportista.calcular(null, LocalDate.of(2026, 11, 13))).isNull();
		assertThat(EdadDeportista.calcular(null, Clock.systemUTC())).isNull();
	}

	@Test
	void laZonaDeLaEscuelaEsBuenosAires() {
		assertThat(EdadDeportista.ZONA_ESCUELA.getId()).isEqualTo("America/Argentina/Buenos_Aires");
	}

	@Test
	void elCumpleaniosCambiaALasCeroHorasDeBuenosAiresNoDeUtc() {
		LocalDate nacimiento = LocalDate.of(2018, 11, 13);
		// 02:59:59Z = 23:59:59 del 12/11 en Buenos Aires (UTC-3): todavia no cumplio.
		assertThat(EdadDeportista.calcular(nacimiento, reloj("2026-11-13T02:59:59Z"))).isEqualTo(7);
		// 03:00:00Z = 00:00:00 del 13/11 en Buenos Aires: ya cumplio.
		assertThat(EdadDeportista.calcular(nacimiento, reloj("2026-11-13T03:00:00Z"))).isEqualTo(8);
	}

	@Test
	void elBordeDelDia1DeMarzoParaQuienNacioUn29DeFebreroUsaLaFechaDeBuenosAires() {
		LocalDate nacimiento = LocalDate.of(2016, 2, 29);
		// 02:30Z del 1/3 es todavia el 28/2 en Buenos Aires: aun no cumplio.
		assertThat(EdadDeportista.calcular(nacimiento, reloj("2026-03-01T02:30:00Z"))).isEqualTo(9);
		assertThat(EdadDeportista.calcular(nacimiento, reloj("2026-03-01T03:00:00Z"))).isEqualTo(10);
	}

	@Test
	void laFechaDeReferenciaEsLaDeHoyEnBuenosAires() {
		assertThat(EdadDeportista.hoy(reloj("2026-11-13T02:59:59Z"))).isEqualTo(LocalDate.of(2026, 11, 12));
		assertThat(EdadDeportista.hoy(reloj("2026-11-13T03:00:00Z"))).isEqualTo(LocalDate.of(2026, 11, 13));
		assertThat(EdadDeportista.calcular(LocalDate.of(2018, 11, 13), reloj("2026-11-13T15:00:00Z")))
				.isEqualTo(EdadDeportista.calcular(LocalDate.of(2018, 11, 13), EdadDeportista.hoy(reloj("2026-11-13T15:00:00Z"))));
	}

	private static Clock reloj(String instante) {
		return Clock.fixed(Instant.parse(instante), ZoneOffset.UTC);
	}
}
