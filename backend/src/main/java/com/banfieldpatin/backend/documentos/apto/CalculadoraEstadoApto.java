package com.banfieldpatin.backend.documentos.apto;

import java.time.LocalDate;

import com.banfieldpatin.backend.documentos.EstadoDocumento;

/** Estado derivado del apto de una temporada a partir de la versión vigente (precedencia en {@link EstadoApto}). Función pura. */
public final class CalculadoraEstadoApto {

    private CalculadoraEstadoApto() {
    }

    /** {@code vigente} es el estado del documento vigente de la serie, o {@code null} si no hay ninguno. */
    public static EstadoApto calcular(EstadoDocumento vigente, ReglaVigenciaApto.Vigencia vigencia, LocalDate hoy) {
        if (vigente == null) {
            return EstadoApto.FALTA;
        }
        return switch (vigente) {
            case OBSERVADO -> EstadoApto.OBSERVADO;
            case CARGADO -> EstadoApto.EN_REVISION;
            case VENCIDO -> EstadoApto.VENCIDO;
            case APROBADO -> ReglaVigenciaApto.vencida(vigencia, hoy) ? EstadoApto.VENCIDO : EstadoApto.CUMPLIDO;
            // PENDIENTE existe en el esquema pero ningún flujo lo produce: no es un documento presentado.
            case PENDIENTE -> EstadoApto.FALTA;
        };
    }
}
