package com.banfieldpatin.backend.compartido.error;

import java.util.List;

import org.springframework.http.HttpStatus;

public class ExcepcionNegocio extends RuntimeException {

	private final HttpStatus estado;
	private final String codigo;
	private final List<DetalleError> detalles;

	public ExcepcionNegocio(HttpStatus estado, String codigo, String mensaje) {
		this(estado, codigo, mensaje, List.of());
	}

	/** {@code detalles} identifica campos o posiciones de la solicitud (nunca valores enviados). */
	public ExcepcionNegocio(HttpStatus estado, String codigo, String mensaje, List<DetalleError> detalles) {
		super(mensaje);
		this.estado = estado;
		this.codigo = codigo;
		this.detalles = List.copyOf(detalles);
	}

	public List<DetalleError> getDetalles() {
		return detalles;
	}

	public HttpStatus getEstado() {
		return estado;
	}

	public String getCodigo() {
		return codigo;
	}
}
