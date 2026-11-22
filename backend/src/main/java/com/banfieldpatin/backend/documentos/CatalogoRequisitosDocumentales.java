package com.banfieldpatin.backend.documentos;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Catálogo mínimo y cálculo del checklist documental del deportista (U20). Hoy solo pide los dos lados del DNI del deportista:
 * es lo que se decidió evaluar, no una regla federativa permanente. Los formularios de la Federación cambian cada año (lo
 * disponible es el de 2026 y los requisitos de 2027 no están confirmados), por eso el catálogo no copia el formulario ni
 * decide nada por edad, por la condición de «menor» del formulario ni por los tutores: esas reglas se resuelven aparte.
 */
public final class CatalogoRequisitosDocumentales {

    /** Los requisitos evaluados hoy, en el orden en que se muestran. */
    public static final List<TipoDocumento> DNI_DEPORTISTA =
            List.of(TipoDocumento.DNI_DEPORTISTA_FRENTE, TipoDocumento.DNI_DEPORTISTA_DORSO);

    private CatalogoRequisitosDocumentales() {
    }

    /**
     * Estado de cada requisito a partir de los documentos VIGENTES del deportista (los demás tipos se ignoran). APROBADO
     * cumple, CARGADO está en revisión, OBSERVADO conserva su motivo y la ausencia es FALTA. Un estado que ningún flujo
     * produce para un DNI (PENDIENTE, VENCIDO) no cumple ni espera una revisión: se informa como FALTA.
     */
    public static ChecklistDocumental calcular(UUID deportistaId, List<DocumentoMetadatos> vigentes) {
        var requisitos = DNI_DEPORTISTA.stream().map(tipo -> requisito(tipo, vigentes)).toList();
        return new ChecklistDocumental(deportistaId, true, ChecklistDocumental.ALCANCE_DNI_DEPORTISTA, requisitos);
    }

    private static RequisitoDocumental requisito(TipoDocumento tipo, List<DocumentoMetadatos> vigentes) {
        // Un solo DNI vigente por tipo (índice único); si hubiera más de uno, el más reciente decide.
        return vigentes.stream().filter(d -> d.vigente() && d.tipo() == tipo)
                .max(Comparator.comparing(DocumentoMetadatos::creadoEn, Comparator.nullsFirst(Comparator.<Instant>naturalOrder())))
                .map(d -> switch (d.estado()) {
                    case APROBADO -> new RequisitoDocumental(tipo, EstadoRequisito.CUMPLIDO, null);
                    case CARGADO -> new RequisitoDocumental(tipo, EstadoRequisito.EN_REVISION, null);
                    case OBSERVADO -> new RequisitoDocumental(tipo, EstadoRequisito.OBSERVADO, d.motivoObservacion());
                    case PENDIENTE, VENCIDO -> new RequisitoDocumental(tipo, EstadoRequisito.FALTA, null);
                })
                .orElseGet(() -> new RequisitoDocumental(tipo, EstadoRequisito.FALTA, null));
    }
}
