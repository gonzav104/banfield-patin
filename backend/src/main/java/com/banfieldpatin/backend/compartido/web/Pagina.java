package com.banfieldpatin.backend.compartido.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Respuesta paginada; los tamanos de pagina se acotan siempre igual (nunca 400 por tamano). */
public record Pagina<T>(List<T> contenido, int pagina, int tamanio, long totalElementos) {

	public static final int TAMANIO_POR_DEFECTO = 20;
	public static final int TAMANIO_MAXIMO = 100;

	public Pagina {
		contenido = List.copyOf(contenido);
	}

	/** Pagina negativa pasa a 0; tamano menor a 1 pasa a 1 y mayor a {@link #TAMANIO_MAXIMO} se recorta. */
	public static Pageable pedir(int pagina, int tamanio) {
		return PageRequest.of(Math.max(pagina, 0), Math.min(Math.max(tamanio, 1), TAMANIO_MAXIMO));
	}

	public static <E, T> Pagina<T> de(Page<E> pagina, Function<E, T> conversor) {
		return new Pagina<>(pagina.getContent().stream().map(conversor).toList(), pagina.getNumber(),
				pagina.getSize(), pagina.getTotalElements());
	}
}
