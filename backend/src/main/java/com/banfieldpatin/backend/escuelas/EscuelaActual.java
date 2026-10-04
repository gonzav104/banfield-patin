package com.banfieldpatin.backend.escuelas;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resuelve la escuela configurada (banfield.escuela.slug) del lado servidor; el cliente nunca envia escuelaId.
 * Cachea solo el id (inmutable) tras la primera resolucion exitosa; los datos mutables (activa) se releen.
 */
@Component
public class EscuelaActual {

	private static final Logger log = LoggerFactory.getLogger(EscuelaActual.class);

	private final EscuelaRepository repositorio;
	private final String slug;
	private volatile UUID idCacheado;

	public EscuelaActual(EscuelaRepository repositorio, @Value("${banfield.escuela.slug}") String slug) {
		this.repositorio = repositorio;
		this.slug = slug;
	}

	public Optional<Escuela> obtener() {
		UUID id = idCacheado;
		if (id != null) {
			return repositorio.findById(id);
		}
		Optional<Escuela> escuela = repositorio.findBySlugIgnoreCase(slug);
		if (escuela.isPresent()) {
			idCacheado = escuela.get().getId();
		} else {
			log.error("La escuela configurada con slug '{}' no existe en la base de datos", slug);
		}
		return escuela;
	}
}
