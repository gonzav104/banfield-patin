package com.banfieldpatin.backend.documentos.apto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Apto físico de un deportista en una temporada, calculado en cada lectura (no se guarda). La presentación es INFORMATIVA y no
 * bloqueante ({@code bloqueante} es siempre {@code false}): no habilita ni impide empadronar, competir ni nada fuera de este
 * dato. {@code documento} es {@code null} cuando falta; trae los datos que declaró la familia pero nunca el archivo, su
 * ubicación ni su hash. {@code revision} solo la recibe ADMIN (la edición con revisión optimista). Los tres datos del apto
 * pertenecen a la familia que cargó esa versión: otra familia con vínculo activo recibe el estado y {@code datosVisibles=false}
 * con los datos en {@code null}; ADMIN siempre los recibe.
 */
public record AptoFisicoTemporada(UUID deportistaId, UUID temporadaId, int anio, EstadoApto estado, boolean bloqueante,
        Vigencia vigencia, List<String> avisos, DocumentoApto documento) {

    public static final String AVISO_REQUISITOS_SIN_CONFIRMAR = "REQUISITOS_DE_LA_TEMPORADA_PENDIENTES_DE_CONFIRMACION";

    public AptoFisicoTemporada {
        avisos = List.copyOf(avisos);
    }

    public record Vigencia(LocalDate desde, LocalDate hasta, boolean confirmada, String fuente) {
    }

    public record DocumentoApto(UUID id, String estado, LocalDate fechaEmision, String medicoNombre, String medicoMatricula,
            String motivoObservacion, Instant cargadoEn, Long revision, boolean datosVisibles) {

        @Override
        public String toString() {
            return "DocumentoApto[" + id + "]";
        }
    }
}
