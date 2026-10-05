package com.banfieldpatin.backend.compartido.auditoria;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;

/** usuarioId y recursoId pueden ser null; detalle nunca debe contener secretos (se filtran claves sensibles). */
public record EventoAuditoria(
		UUID escuelaId,
		UUID usuarioId,
		AccionAuditoria accion,
		String recursoTipo,
		UUID recursoId,
		Map<String, Object> detalle,
		DatosSolicitud solicitud) {

	public EventoAuditoria {
		// Copia inmutable que tolera valores nulos (p. ej. anteriorVinculoId cuando no habia principal previo).
		detalle = detalle == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(detalle));
		solicitud = solicitud == null ? DatosSolicitud.NINGUNA : solicitud;
	}
}
