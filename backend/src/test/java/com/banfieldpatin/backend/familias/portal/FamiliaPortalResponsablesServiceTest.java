package com.banfieldpatin.backend.familias.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.familias.portal.dto.ResponsableDeFamilia;
import com.banfieldpatin.backend.familias.responsables.TipoVinculoTutor;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

class FamiliaPortalResponsablesServiceTest {

	private final FamiliaDeportistaRepository vinculos = mock(FamiliaDeportistaRepository.class);
	private final FamiliaPortalResponsablesService servicio = new FamiliaPortalResponsablesService(vinculos);
	private final UUID escuela = UUID.randomUUID();
	private final UUID familia = UUID.randomUUID();
	private final UUID deportista = UUID.randomUUID();
	private final UsuarioAutenticado usuario = new UsuarioAutenticado(UUID.randomUUID(), escuela, familia, Rol.FAMILIA);

	@Test
	void unaCuentaSinFamiliaDaElMismo404SinConsultarLaBase() {
		var sinFamilia = new UsuarioAutenticado(UUID.randomUUID(), escuela, null, Rol.FAMILIA);
		assertThatThrownBy(() -> servicio.responsables(sinFamilia, deportista)).isInstanceOfSatisfying(
				ExcepcionNegocio.class, e -> assertThat(e.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO"));
		verifyNoInteractions(vinculos);
	}

	@Test
	void unDeportistaFueraDeAlcanceDaElMismo404YNoLeeResponsablesNiSiquiera() {
		when(vinculos.deportistaEsDelPortal(familia, escuela, deportista)).thenReturn(false);
		assertThatThrownBy(() -> servicio.responsables(usuario, deportista)).isInstanceOfSatisfying(
				ExcepcionNegocio.class, e -> {
					assertThat(e.getEstado().value()).isEqualTo(404);
					assertThat(e.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO");
				});
		verify(vinculos).deportistaEsDelPortal(familia, escuela, deportista);
		verifyNoMoreInteractions(vinculos);
	}

	@Test
	void laIdentidadSaleSoloDelUsuarioAutenticadoYLaListaVaciaEsUnaRespuestaValida() {
		when(vinculos.deportistaEsDelPortal(familia, escuela, deportista)).thenReturn(true);
		when(vinculos.responsablesDelPortal(familia, escuela, deportista)).thenReturn(List.of());
		assertThat(servicio.responsables(usuario, deportista).responsables()).isEmpty();
		var uno = new ResponsableDeFamilia(UUID.randomUUID(), "Ana", "Paz", TipoVinculoTutor.MADRE);
		when(vinculos.responsablesDelPortal(familia, escuela, deportista)).thenReturn(List.of(uno));
		assertThat(servicio.responsables(usuario, deportista).responsables()).containsExactly(uno);
	}

	@Test
	void elDtoFamiliarTieneExactamenteCuatroCamposYNoImprimeDatosPersonales() {
		assertThat(ResponsableDeFamilia.class.getRecordComponents()).extracting("name")
				.containsExactly("tutorId", "nombre", "apellido", "tipo");
		assertThat(new ResponsableDeFamilia(UUID.randomUUID(), "Ana", "Paz", null).toString())
				.doesNotContain("Ana").doesNotContain("Paz");
	}
}
