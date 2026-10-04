package com.banfieldpatin.backend.compartido.error;

import org.springframework.http.HttpStatus;

public class ExcepcionNegocio extends RuntimeException {

	private final HttpStatus estado;
	private final String codigo;

	public ExcepcionNegocio(HttpStatus estado, String codigo, String mensaje) {
		super(mensaje);
		this.estado = estado;
		this.codigo = codigo;
	}

	public HttpStatus getEstado() {
		return estado;
	}

	public String getCodigo() {
		return codigo;
	}
}
