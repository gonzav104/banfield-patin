package com.banfieldpatin.backend.documentos.apto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;

class MetadatosAptoTest {
    /** 2026-06-15 02:00 UTC es todavía 2026-06-14 en Buenos Aires: la fecha «de hoy» se calcula en la zona de la escuela. */
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-06-15T02:00:00Z"), ZoneOffset.UTC);

    private static MetadatosApto.Solicitado pedido(String fecha, String nombre, String matricula) {
        return new MetadatosApto.Solicitado(fecha, nombre, matricula);
    }

    private static ExcepcionNegocio rechazo(MetadatosApto.Solicitado s) {
        return (ExcepcionNegocio) catchThrowable(s);
    }

    private static Throwable catchThrowable(MetadatosApto.Solicitado s) {
        return org.assertj.core.api.Assertions.catchThrowable(() -> MetadatosApto.validar(s, RELOJ));
    }

    @Test
    void normalizaConTrimYAceptaLaFechaDeHoyEnLaZonaDeLaEscuela() {
        var m = MetadatosApto.validar(pedido(" 2026-06-14 ", "  Medico Sintetico ", " MP 0001  "), RELOJ);
        assertThat(m).isEqualTo(new MetadatosApto(LocalDate.of(2026, 6, 14), "Medico Sintetico", "MP 0001"));
    }

    @Test
    void laFechaDeMananaEnLaZonaDeLaEscuelaEsFutura() {
        assertThat(rechazo(pedido("2026-06-15", "Medico Sintetico", "MP 1"))).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "15/06/2026", "2026-13-01", "1999-12-31", "2026-06-20"})
    void rechazaFechasInvalidasFuturasOAnterioresA2000(String fecha) {
        var e = rechazo(pedido(fecha, "Medico Sintetico", "MP 1"));
        assertThat(e).isNotNull();
        assertThat(e.getMessage()).doesNotContain(fecha.isBlank() ? "\u0000" : fecha);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MP 12.345", "M.N. 123/4", "ABC-99", "1234", "mp0001"})
    void aceptaMatriculasAlfanumericasConSeparadores(String matricula) {
        assertThat(MetadatosApto.validar(pedido("2026-06-01", "Medico Sintetico", matricula), RELOJ).medicoMatricula())
                .isEqualTo(matricula);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "***", "-1", "MP\n1", "MP<script>"})
    void rechazaMatriculasVaciasOConCaracteresNoPermitidos(String matricula) {
        assertThat(rechazo(pedido("2026-06-01", "Medico Sintetico", matricula))).isNotNull();
    }

    @Test
    void acotaLongitudes() {
        assertThat(MetadatosApto.validar(pedido("2026-06-01", "M".repeat(150), "9".repeat(50)), RELOJ)).isNotNull();
        assertThat(rechazo(pedido("2026-06-01", "M".repeat(151), "MP 1"))).isNotNull();
        assertThat(rechazo(pedido("2026-06-01", "Medico Sintetico", "9".repeat(51)))).isNotNull();
    }

    @Test
    void elNombreNoAdmiteCaracteresDeControl() {
        assertThat(rechazo(pedido("2026-06-01", "Medico\nSintetico", "MP 1"))).isNotNull();
        assertThat(rechazo(pedido("2026-06-01", null, "MP 1"))).isNotNull();
    }

    @Test
    void elErrorListaSoloNombresDeCamposNuncaValores() {
        var e = rechazo(pedido("no-es-fecha", "Medico Sintetico", "***"));
        assertThat(e.getMessage()).doesNotContain("no-es-fecha", "***", "Medico Sintetico");
        assertThat(e.getDetalles()).extracting(d -> d.campo()).containsExactlyInAnyOrder("fechaEmision", "medicoMatricula");
    }

    @Test
    void ningunaRepresentacionTextualExponeLosDatos() {
        var m = new MetadatosApto(LocalDate.of(2026, 6, 1), "Medico Sintetico", "MP 0001");
        assertThat(m.toString()).doesNotContain("Medico", "MP 0001", "2026");
        assertThat(pedido("2026-06-01", "Medico Sintetico", "MP 0001").toString()).doesNotContain("Medico", "MP 0001");
        assertThatThrownBy(() -> MetadatosApto.validar(pedido(null, null, null), RELOJ)).isInstanceOf(ExcepcionNegocio.class);
    }
}
