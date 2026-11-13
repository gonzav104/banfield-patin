package com.banfieldpatin.backend.familias.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.familias.portal.dto.DatosBaseDeFicha;
import com.banfieldpatin.backend.familias.portal.dto.FichaDeportistaDeFamilia;
import com.banfieldpatin.backend.familias.portal.dto.TemporadaDeFamilia;
import com.banfieldpatin.backend.familias.portal.dto.TemporadaVigenteDeFicha;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.banfieldpatin.backend.usuarios.Rol;

class FamiliaPortalFichaServiceTest {

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final UUID deportistaId = UUID.randomUUID();
	private final UsuarioAutenticado usuario = new UsuarioAutenticado(UUID.randomUUID(), escuelaId, familiaId, Rol.FAMILIA);
	private final UsuarioAutenticado sinFamilia = new UsuarioAutenticado(UUID.randomUUID(), escuelaId, null, Rol.FAMILIA);
	private FamiliaDeportistaRepository vinculos;
	private FamiliaPortalFichaService servicio;

	@BeforeEach
	void preparar() {
		vinculos = mock(FamiliaDeportistaRepository.class);
		// 2026-11-13 en Buenos Aires.
		servicio = new FamiliaPortalFichaService(vinculos,
				Clock.fixed(Instant.parse("2026-11-13T15:00:00Z"), ZoneOffset.UTC));
	}

	private static void assert404(Throwable e) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, n -> {
			assertThat(n.getEstado()).isEqualTo(HttpStatus.NOT_FOUND);
			assertThat(n.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO");
		});
	}

	private TemporadaDeFamilia vigente() {
		return new TemporadaDeFamilia(UUID.randomUUID(), (short) 2026, true, "Sub 12", "Femenino", null, "007",
				EstadoDeportivo.SUSPENDIDO);
	}

	@Test
	void unaCuentaFamiliaSinFamiliaNuncaConsultaLaBaseYRecibeElMismoNotFound() {
		assertThatThrownBy(() -> servicio.ficha(sinFamilia, deportistaId)).satisfies(FamiliaPortalFichaServiceTest::assert404);

		verifyNoInteractions(vinculos);
	}

	@Test
	void usaLaFamiliaYLaEscuelaDelUsuarioEnLasDosConsultas() {
		when(vinculos.fichaBaseDelPortal(familiaId, escuelaId, deportistaId))
				.thenReturn(Optional.of(new DatosBaseDeFicha("Ana", "Paz", LocalDate.of(2016, 5, 1), true)));
		when(vinculos.temporadaVigenteDelPortal(familiaId, escuelaId, deportistaId)).thenReturn(Optional.of(vigente()));

		FichaDeportistaDeFamilia ficha = servicio.ficha(usuario, deportistaId);

		verify(vinculos).fichaBaseDelPortal(familiaId, escuelaId, deportistaId);
		verify(vinculos).temporadaVigenteDelPortal(familiaId, escuelaId, deportistaId);
		assertThat(ficha.nombre()).isEqualTo("Ana");
		assertThat(ficha.apellido()).isEqualTo("Paz");
		assertThat(ficha.activo()).isTrue();
		assertThat(ficha.edad()).isEqualTo(10);
		assertThat(ficha.temporadaVigente()).isEqualTo(
				new TemporadaVigenteDeFicha((short) 2026, "Sub 12", "Femenino", null, "007", EstadoDeportivo.SUSPENDIDO));
	}

	@Test
	void sinDatosDeLaTemporadaVigenteLaFichaIgualmenteSeDevuelveConLaTemporadaNula() {
		when(vinculos.fichaBaseDelPortal(familiaId, escuelaId, deportistaId))
				.thenReturn(Optional.of(new DatosBaseDeFicha("Ana", "Paz", null, false)));
		when(vinculos.temporadaVigenteDelPortal(familiaId, escuelaId, deportistaId)).thenReturn(Optional.empty());

		FichaDeportistaDeFamilia ficha = servicio.ficha(usuario, deportistaId);

		assertThat(ficha.temporadaVigente()).isNull();
		assertThat(ficha.edad()).isNull();
		assertThat(ficha.activo()).isFalse();
	}

	@Test
	void unDeportistaFueraDeAlcanceEsElNotFoundUniformeYNuncaBuscaLaTemporada() {
		when(vinculos.fichaBaseDelPortal(familiaId, escuelaId, deportistaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.ficha(usuario, deportistaId)).satisfies(FamiliaPortalFichaServiceTest::assert404);

		verify(vinculos, never()).temporadaVigenteDelPortal(org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void laRespuestaTieneUnaListaCerradaDeCamposYNoImprimeDatosPersonales() {
		assertThat(Arrays.stream(FichaDeportistaDeFamilia.class.getRecordComponents()).map(RecordComponent::getName).toList())
				.containsExactly("nombre", "apellido", "edad", "activo", "temporadaVigente");
		assertThat(Arrays.stream(TemporadaVigenteDeFicha.class.getRecordComponents()).map(RecordComponent::getName).toList())
				.containsExactly("anio", "categoria", "rama", "divisional", "numeroCorredor", "estadoDeportivo");
		var ficha = new FichaDeportistaDeFamilia("Ana", "Paz", 9, true, null);
		var base = new DatosBaseDeFicha("Ana", "Paz", LocalDate.of(2016, 5, 1), true);
		for (String texto : List.of(ficha.toString(), base.toString())) {
			assertThat(texto).doesNotContain("Ana", "Paz", "2016");
		}
	}

	@Test
	void laLecturaCorreEnUnaUnicaInstantaneaDeSoloLectura() throws Exception {
		Transactional transaccion = FamiliaPortalFichaService.class
				.getMethod("ficha", UsuarioAutenticado.class, UUID.class).getAnnotation(Transactional.class);

		assertThat(transaccion).isNotNull();
		assertThat(transaccion.readOnly()).isTrue();
		assertThat(transaccion.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
	}
}
