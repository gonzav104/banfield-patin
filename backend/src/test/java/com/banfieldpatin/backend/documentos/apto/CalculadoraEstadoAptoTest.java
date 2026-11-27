package com.banfieldpatin.backend.documentos.apto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.banfieldpatin.backend.documentos.EstadoDocumento;

class CalculadoraEstadoAptoTest {
    private static final ReglaVigenciaApto.Vigencia V2026 = ReglaVigenciaApto.paraTemporada(2026);
    private static final LocalDate EN_TEMPORADA = LocalDate.of(2026, 6, 1);
    private static final LocalDate DESPUES = LocalDate.of(2027, 1, 1);

    private static EstadoApto calcular(EstadoDocumento e, LocalDate hoy) {
        return CalculadoraEstadoApto.calcular(e, V2026, hoy);
    }

    @Test
    void sinDocumentoVigenteFalta() {
        assertThat(calcular(null, EN_TEMPORADA)).isEqualTo(EstadoApto.FALTA);
        assertThat(calcular(null, DESPUES)).isEqualTo(EstadoApto.FALTA);
    }

    @Test
    void cargadoEstaEnRevisionYObservadoSigueObservadoAunqueLaTemporadaTermine() {
        assertThat(calcular(EstadoDocumento.CARGADO, EN_TEMPORADA)).isEqualTo(EstadoApto.EN_REVISION);
        assertThat(calcular(EstadoDocumento.OBSERVADO, EN_TEMPORADA)).isEqualTo(EstadoApto.OBSERVADO);
        // El vencimiento solo reemplaza a una aprobación: lo pendiente sigue pendiente.
        assertThat(calcular(EstadoDocumento.CARGADO, DESPUES)).isEqualTo(EstadoApto.EN_REVISION);
        assertThat(calcular(EstadoDocumento.OBSERVADO, DESPUES)).isEqualTo(EstadoApto.OBSERVADO);
    }

    @Test
    void aprobadoCumpleHastaElFinDeLaTemporadaYDespuesVence() {
        assertThat(calcular(EstadoDocumento.APROBADO, EN_TEMPORADA)).isEqualTo(EstadoApto.CUMPLIDO);
        assertThat(calcular(EstadoDocumento.APROBADO, LocalDate.of(2026, 12, 31))).isEqualTo(EstadoApto.CUMPLIDO);
        assertThat(calcular(EstadoDocumento.APROBADO, DESPUES)).isEqualTo(EstadoApto.VENCIDO);
    }

    @Test
    void unVencidoPersistidoEsVencidoYPendienteNoCuentaComoPresentado() {
        assertThat(calcular(EstadoDocumento.VENCIDO, EN_TEMPORADA)).isEqualTo(EstadoApto.VENCIDO);
        assertThat(calcular(EstadoDocumento.PENDIENTE, EN_TEMPORADA)).isEqualTo(EstadoApto.FALTA);
    }
}
