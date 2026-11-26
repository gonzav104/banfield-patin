package com.banfieldpatin.backend.documentos.apto;

/**
 * Estado derivado del apto de un deportista en una temporada. Precedencia (se evalúa en este orden sobre la versión VIGENTE;
 * las históricas nunca cuentan):
 * <ol>
 * <li>sin documento vigente: {@link #FALTA};</li>
 * <li>documento {@code OBSERVADO}: {@link #OBSERVADO} (la familia debe reemplazarlo);</li>
 * <li>documento {@code CARGADO}: {@link #EN_REVISION};</li>
 * <li>documento {@code VENCIDO} persistido: {@link #VENCIDO};</li>
 * <li>documento {@code APROBADO}: {@link #VENCIDO} si hoy es posterior al fin de vigencia de la temporada, si no {@link #CUMPLIDO}.</li>
 * </ol>
 * El vencimiento solo reemplaza a una aprobación: un apto pendiente u observado de una temporada ya terminada sigue pendiente u
 * observado, porque todavía hay una decisión por tomar.
 */
public enum EstadoApto {
    FALTA, EN_REVISION, OBSERVADO, CUMPLIDO, VENCIDO
}
