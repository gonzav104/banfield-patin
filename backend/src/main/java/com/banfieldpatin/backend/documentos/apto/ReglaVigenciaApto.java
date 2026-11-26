package com.banfieldpatin.backend.documentos.apto;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

/**
 * Vigencia del apto físico de una temporada. La ÚNICA regla confirmada es la de 2026 (Reglamento Nacional 2026): vigencia calendario
 * del 01/01 al 31/12 del año de la temporada, «independientemente de la fecha de emisión». Para cualquier otra temporada se aplica la
 * misma fecha de forma <b>provisional</b> y se marca como no confirmada: no hay formulario ni reglamento verificados. El vencimiento se
 * calcula al leer con el día de hoy en la zona de la escuela, nunca se persiste; así, confirmar o cambiar la regla de una temporada
 * no deja fechas viejas guardadas.
 */
public final class ReglaVigenciaApto {

    /** Zona de la escuela: la misma con la que se calculan las edades. */
    public static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");

    /** Temporadas cuya regla está verificada contra una fuente documentada. */
    private static final Set<Integer> CONFIRMADAS = Set.of(2026);

    private ReglaVigenciaApto() {
    }

    /** {@code confirmada=false}: la regla es provisional; la interfaz debe decirlo. */
    public record Vigencia(LocalDate desde, LocalDate hasta, boolean confirmada, String fuente) {
    }

    public static Vigencia paraTemporada(int anio) {
        boolean confirmada = CONFIRMADAS.contains(anio);
        return new Vigencia(LocalDate.of(anio, 1, 1), LocalDate.of(anio, 12, 31), confirmada,
                confirmada ? "Reglamento Nacional 2026" : "Provisional: requisitos de esta temporada sin confirmar");
    }

    /** Vigente significa que hoy está dentro del año calendario de la temporada; el 31/12 todavía es válido. */
    public static boolean vencida(Vigencia vigencia, LocalDate hoy) {
        return hoy.isAfter(vigencia.hasta());
    }
}
