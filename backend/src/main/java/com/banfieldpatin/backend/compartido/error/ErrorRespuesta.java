package com.banfieldpatin.backend.compartido.error;

import java.util.List;

public record ErrorRespuesta(String codigo, String mensaje, List<DetalleError> detalles) {

	public ErrorRespuesta {
		detalles = detalles == null ? List.of() : List.copyOf(detalles);
	}

	public static ErrorRespuesta de(String codigo, String mensaje) {
		return new ErrorRespuesta(codigo, mensaje, List.of());
	}
}
