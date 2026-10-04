package com.banfieldpatin.backend.compartido.auditoria;

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
		detalle = detalle == null ? Map.of() : Map.copyOf(detalle);
		solicitud = solicitud == null ? DatosSolicitud.NINGUNA : solicitud;
	}
}
