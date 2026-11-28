package com.banfieldpatin.backend.familias.tutores.verificacion.dto;

import java.util.List;
import java.util.UUID;

/**
 * Resultado individual de cada tutor del lote, en el orden pedido. {@code resultado}: {@code VERIFICADO} (se verificó ahora),
 * {@code YA_VERIFICADO} (no cambió nada), o el código del motivo por el que no se verificó ({@code EDICION_DESACTUALIZADA},
 * {@code TUTOR_NO_ENCONTRADO}, {@code ESTADO_INCOMPATIBLE}, {@code FAMILIA_INACTIVA}, {@code OCUPADO}), {@code ERROR} (falla
 * inesperada de ese tutor, sin cambios) o {@code NO_PROCESADO} (se agotó el tiempo del lote antes de llegar a él: reintentarlo).
 * {@code version} es la revisión resultante cuando se verificó.
 */
public record ResultadoLote(List<Item> resultados) {

	public record Item(UUID tutorId, String resultado, String version) {
	}
}
