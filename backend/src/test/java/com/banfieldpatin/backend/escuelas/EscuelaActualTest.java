package com.banfieldpatin.backend.escuelas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.banfieldpatin.backend.FixturesDominio;

class EscuelaActualTest {

	private final EscuelaRepository repositorio = mock(EscuelaRepository.class);
	private final EscuelaActual escuelaActual = new EscuelaActual(repositorio, "escuela-test");

	@Test
	void cacheaElIdTrasLaPrimeraResolucionYReleeLosDatosPorId() {
		UUID id = UUID.randomUUID();
		Escuela escuela = FixturesDominio.escuela(id, true);
		when(repositorio.findBySlugIgnoreCase("escuela-test")).thenReturn(Optional.of(escuela));
		when(repositorio.findById(id)).thenReturn(Optional.of(escuela));

		assertThat(escuelaActual.obtener()).contains(escuela);
		assertThat(escuelaActual.obtener()).contains(escuela);

		verify(repositorio, times(1)).findBySlugIgnoreCase("escuela-test");
		verify(repositorio, times(1)).findById(id);
	}

	@Test
	void slugInexistenteDevuelveVacioYNoCachea() {
		when(repositorio.findBySlugIgnoreCase("escuela-test")).thenReturn(Optional.empty());

		assertThat(escuelaActual.obtener()).isEmpty();
		assertThat(escuelaActual.obtener()).isEmpty();

		verify(repositorio, times(2)).findBySlugIgnoreCase("escuela-test");
		verify(repositorio, never()).findById(org.mockito.ArgumentMatchers.any());
	}
}
