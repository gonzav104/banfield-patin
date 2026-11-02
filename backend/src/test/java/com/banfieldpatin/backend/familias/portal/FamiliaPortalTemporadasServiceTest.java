package com.banfieldpatin.backend.familias.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.familias.portal.dto.TemporadaDeFamilia;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.banfieldpatin.backend.usuarios.Rol;

class FamiliaPortalTemporadasServiceTest {

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final UUID deportistaId = UUID.randomUUID();
	private final UUID temporadaId = UUID.randomUUID();
	private final UsuarioAutenticado usuario = new UsuarioAutenticado(UUID.randomUUID(), escuelaId, familiaId, Rol.FAMILIA);
	private final UsuarioAutenticado sinFamilia = new UsuarioAutenticado(UUID.randomUUID(), escuelaId, null, Rol.FAMILIA);

	private FamiliaDeportistaRepository vinculos;
	private FamiliaPortalTemporadasService servicio;

	@BeforeEach
	void preparar() {
		vinculos = mock(FamiliaDeportistaRepository.class);
		servicio = new FamiliaPortalTemporadasService(vinculos);
	}

	private TemporadaDeFamilia temporada(int anio) {
		return new TemporadaDeFamilia(UUID.randomUUID(), (short) anio, false, "Sub 12", null, null, "007",
				EstadoDeportivo.ACTIVO);
	}

	private static void assert404(Throwable e, String codigo) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, n -> {
			assertThat(n.getEstado()).isEqualTo(HttpStatus.NOT_FOUND);
			assertThat(n.getCodigo()).isEqualTo(codigo);
		});
	}

	@Test
	void unaCuentaFamiliaSinFamiliaNuncaConsultaLaBaseYRecibeElMismoNotFound() {
		assertThatThrownBy(() -> servicio.historial(sinFamilia, deportistaId))
				.satisfies(e -> assert404(e, "DEPORTISTA_NO_ENCONTRADO"));
		assertThatThrownBy(() -> servicio.temporada(sinFamilia, deportistaId, temporadaId))
				.satisfies(e -> assert404(e, "DEPORTISTA_TEMPORADA_NO_ENCONTRADA"));

		verifyNoInteractions(vinculos);
	}

	@Test
	void elHistorialUsaLaFamiliaYLaEscuelaDelUsuarioYConFilasNoHaceUnaSegundaConsulta() {
		List<TemporadaDeFamilia> filas = List.of(temporada(2027), temporada(2026));
		when(vinculos.temporadasDelPortal(familiaId, escuelaId, deportistaId)).thenReturn(filas);

		assertThat(servicio.historial(usuario, deportistaId)).isEqualTo(filas);

		verify(vinculos, never()).deportistaEnAlcance(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any());
	}

	@Test
	void unHistorialVacioDeUnDeportistaPropioEsUnaListaVaciaYDeUnoAjenoEsNotFound() {
		when(vinculos.temporadasDelPortal(familiaId, escuelaId, deportistaId)).thenReturn(List.of());
		when(vinculos.deportistaEnAlcance(familiaId, escuelaId, deportistaId)).thenReturn(Optional.of(deportistaId));
		assertThat(servicio.historial(usuario, deportistaId)).isEmpty();

		when(vinculos.deportistaEnAlcance(familiaId, escuelaId, deportistaId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> servicio.historial(usuario, deportistaId))
				.satisfies(e -> assert404(e, "DEPORTISTA_NO_ENCONTRADO"));
	}

	@Test
	void elDetalleEsUnaSolaConsultaYCualquierAusenciaDaElMismoNotFoundSinDiagnosticar() {
		TemporadaDeFamilia fila = temporada(2026);
		when(vinculos.temporadaDelPortal(familiaId, escuelaId, deportistaId, temporadaId)).thenReturn(Optional.of(fila));
		assertThat(servicio.temporada(usuario, deportistaId, temporadaId)).isEqualTo(fila);

		when(vinculos.temporadaDelPortal(familiaId, escuelaId, deportistaId, temporadaId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> servicio.temporada(usuario, deportistaId, temporadaId))
				.satisfies(e -> assert404(e, "DEPORTISTA_TEMPORADA_NO_ENCONTRADA"));

		// No se averigua POR QUE falta (deportista ajeno, temporada oculta, inexistente...): no hay segunda consulta.
		verify(vinculos, never()).deportistaEnAlcance(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any());
	}

	@Test
	void losMensajesDeNotFoundSonFijosYNoNombranIdsNiMotivos() {
		when(vinculos.temporadasDelPortal(familiaId, escuelaId, deportistaId)).thenReturn(List.of());
		when(vinculos.deportistaEnAlcance(familiaId, escuelaId, deportistaId)).thenReturn(Optional.empty());
		when(vinculos.temporadaDelPortal(familiaId, escuelaId, deportistaId, temporadaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.historial(usuario, deportistaId)).hasMessage("El deportista no existe.");
		assertThatThrownBy(() -> servicio.temporada(usuario, deportistaId, temporadaId))
				.hasMessage("El deportista no tiene datos cargados en esa temporada.");
	}
}
