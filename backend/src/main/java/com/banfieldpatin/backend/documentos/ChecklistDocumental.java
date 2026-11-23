package com.banfieldpatin.backend.documentos;

import java.util.List;
import java.util.UUID;

/**
 * Checklist documental PARCIAL de un deportista (U20). {@code parcial} es siempre {@code true} y {@code alcance} nombra
 * lo único que se evalúa: no es la elegibilidad para empadronarse, no hay estado «listo para presentar» ni porcentaje
 * global, y no incluye documentos de tutores ni la ficha médica.
 */
public record ChecklistDocumental(UUID deportistaId, boolean parcial, String alcance, List<RequisitoDocumental> requisitos) {

    public static final String ALCANCE_DNI_DEPORTISTA = "DNI_DEPORTISTA";

    public ChecklistDocumental {
        requisitos = List.copyOf(requisitos);
    }
}
