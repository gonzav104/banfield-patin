package com.banfieldpatin.backend.documentos.apto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ReglaVigenciaAptoTest {

    @Test
    void laRegla2026EsCalendarioYEstaConfirmada() {
        var v = ReglaVigenciaApto.paraTemporada(2026);
        assertThat(v.desde()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(v.hasta()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(v.confirmada()).isTrue();
        assertThat(v.fuente()).contains("2026");
    }

    @Test
    void cualquierOtraTemporadaUsaLaMismaFechaPeroSeMarcaProvisional() {
        for (int anio : new int[] {2025, 2027, 2030}) {
            var v = ReglaVigenciaApto.paraTemporada(anio);
            assertThat(v.hasta()).isEqualTo(LocalDate.of(anio, 12, 31));
            assertThat(v.confirmada()).as("temporada %d", anio).isFalse();
        }
    }

    @Test
    void elTreintaYUnoDeDiciembreTodaviaEsValidoYElPrimeroDeEneroYaVencio() {
        var v = ReglaVigenciaApto.paraTemporada(2026);
        assertThat(ReglaVigenciaApto.vencida(v, LocalDate.of(2026, 12, 31))).isFalse();
        assertThat(ReglaVigenciaApto.vencida(v, LocalDate.of(2027, 1, 1))).isTrue();
        assertThat(ReglaVigenciaApto.vencida(v, LocalDate.of(2026, 1, 1))).isFalse();
    }

    @Test
    void laZonaEsLaDeLaEscuela() {
        assertThat(ReglaVigenciaApto.ZONA.getId()).isEqualTo("America/Argentina/Buenos_Aires");
    }
}
