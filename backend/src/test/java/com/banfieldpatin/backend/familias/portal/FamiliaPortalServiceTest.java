package com.banfieldpatin.backend.familias.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.FixturesDominio;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.portal.dto.DeportistaDeFamilia;
import com.banfieldpatin.backend.familias.portal.dto.DeportistaDeFamiliaDetalle;
import com.banfieldpatin.backend.familias.portal.dto.MiFamiliaRespuesta;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/**
 * La identidad sale SOLO del UsuarioAutenticado; el servicio no audita (las lecturas del portal no se auditan); una cuenta
 * FAMILIA sin familiaId no consulta nada; los fallos de alcance son los mismos 404 con mensaje fijo.
 */
class FamiliaPortalServiceTest {

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final UUID usuarioId = UUID.randomUUID();
	private final UsuarioAutenticado familia = new UsuarioAutenticado(usuarioId, escuelaId, familiaId, Rol.FAMILIA);

	private FamiliaRepository familias;
	private TutorRepository tutores;
	private FamiliaDeportistaRepository vinculos;
	private FamiliaPortalService servicio;

	@BeforeEach
	void preparar() {
		familias = mock(FamiliaRepository.class);
		tutores = mock(TutorRepository.class);
		vinculos = mock(FamiliaDeportistaRepository.class);
		servicio = new FamiliaPortalService(familias, tutores, vinculos);
	}

	private static void assertError(Throwable e, HttpStatus estado, String codigo, String mensaje) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, ex -> {
			assertThat(ex.getEstado()).isEqualTo(estado);
			assertThat(ex.getCodigo()).isEqualTo(codigo);
			assertThat(ex.getMessage()).isEqualTo(mensaje);
			assertThat(ex.getDetalles()).isEmpty();
		});
	}

	// ---------- mi-familia ----------

	@Test
	void miFamiliaConsultaConLaFamiliaYLaEscuelaDelUsuarioYDevuelveSusTutoresActivos() {
		when(familias.buscarDelPortal(familiaId, escuelaId))
				.thenReturn(Optional.of(FixturesDominio.familia(familiaId, escuelaId, true)));
		when(tutores.activosDeFamilia(escuelaId, familiaId)).thenReturn(List.of(
				FixturesDominio.tutor(UUID.randomUUID(), escuelaId, familiaId, "Ana", "Perez"),
				FixturesDominio.tutor(UUID.randomUUID(), escuelaId, familiaId, "Beto", "Perez")));

		MiFamiliaRespuesta r = servicio.miFamilia(familia);

		assertThat(r.id()).isEqualTo(familiaId);
		assertThat(r.nombreReferencia()).isEqualTo("Familia Prueba");
		assertThat(r.tutores()).extracting(t -> t.nombre()).containsExactly("Ana", "Beto");
		verify(familias).buscarDelPortal(familiaId, escuelaId);
		verify(tutores).activosDeFamilia(escuelaId, familiaId);
		verifyNoMoreInteractions(familias, tutores);
		verifyNoInteractions(vinculos);
	}

	@Test
	void miFamiliaSinResultadoDelAlcanceDa404FamiliaNoEncontradaSinConsultarTutores() {
		when(familias.buscarDelPortal(familiaId, escuelaId)).thenReturn(Optional.empty());

		assertError(org.assertj.core.api.Assertions.catchThrowable(() -> servicio.miFamilia(familia)), HttpStatus.NOT_FOUND,
				"FAMILIA_NO_ENCONTRADA", "La familia no existe.");

		verifyNoInteractions(tutores);
	}

	// ---------- deportistas ----------

	@Test
	void elListadoUsaLaFamiliaYLaEscuelaDelUsuarioYLaPaginaPedida() {
		var pagina = PageRequest.of(2, 5);
		var fila = new DeportistaDeFamilia(UUID.randomUUID(), "Lola", "Gomez", LocalDate.of(2015, 1, 2), false);
		when(vinculos.deportistasDelPortal(familiaId, escuelaId, pagina)).thenReturn(new PageImpl<>(List.of(fila), pagina, 11));

		Pagina<DeportistaDeFamilia> r = servicio.deportistas(familia, pagina);

		assertThat(r.contenido()).containsExactly(fila);
		assertThat(r.contenido().get(0).activo()).as("el inactivo se ve con activo=false").isFalse();
		assertThat(r.pagina()).isEqualTo(2);
		assertThat(r.tamanio()).isEqualTo(5);
		assertThat(r.totalElementos()).isEqualTo(11);
		verify(vinculos).deportistasDelPortal(familiaId, escuelaId, pagina);
		verifyNoMoreInteractions(vinculos);
	}

	@Test
	void unListadoSinVinculosActivosEsUnaPaginaVaciaNuncaUnError() {
		var pagina = PageRequest.of(0, 20);
		Page<DeportistaDeFamilia> vacia = new PageImpl<>(List.of(), pagina, 0);
		when(vinculos.deportistasDelPortal(familiaId, escuelaId, pagina)).thenReturn(vacia);

		Pagina<DeportistaDeFamilia> r = servicio.deportistas(familia, pagina);

		assertThat(r.contenido()).isEmpty();
		assertThat(r.totalElementos()).isZero();
	}

	// ---------- detalle ----------

	@Test
	void elDetalleSeBuscaConElAlcanceDeLaFamiliaYElIdSolicitado() {
		UUID deportistaId = UUID.randomUUID();
		var detalle = detalle(deportistaId);
		when(vinculos.deportistaDelPortal(familiaId, escuelaId, deportistaId)).thenReturn(Optional.of(detalle));

		assertThat(servicio.deportista(familia, deportistaId)).isSameAs(detalle);

		verify(vinculos).deportistaDelPortal(familiaId, escuelaId, deportistaId);
		verifyNoMoreInteractions(vinculos);
		verifyNoInteractions(familias, tutores);
	}

	@Test
	void unDetalleFueraDelAlcanceDa404UniformeConMensajeFijoYSinMasConsultas() {
		UUID ajeno = UUID.randomUUID();
		UUID aleatorio = UUID.randomUUID();
		when(vinculos.deportistaDelPortal(any(), any(), any())).thenReturn(Optional.empty());

		Throwable deOtraFamilia = org.assertj.core.api.Assertions.catchThrowable(() -> servicio.deportista(familia, ajeno));
		Throwable inexistente = org.assertj.core.api.Assertions.catchThrowable(() -> servicio.deportista(familia, aleatorio));

		assertError(deOtraFamilia, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO", "El deportista no existe.");
		assertError(inexistente, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO", "El deportista no existe.");
		// Las dos fallas son indistinguibles: mismo estado, codigo, mensaje y detalles; y el servicio no hizo nada mas.
		ExcepcionNegocio a = (ExcepcionNegocio) deOtraFamilia;
		ExcepcionNegocio b = (ExcepcionNegocio) inexistente;
		assertThat(a.getMessage()).isEqualTo(b.getMessage());
		assertThat(a.getCodigo()).isEqualTo(b.getCodigo());
		verify(vinculos).deportistaDelPortal(familiaId, escuelaId, ajeno);
		verify(vinculos).deportistaDelPortal(familiaId, escuelaId, aleatorio);
		verifyNoMoreInteractions(vinculos);
		verifyNoInteractions(familias, tutores);
	}

	// ---------- identidad ----------

	@Test
	void unaCuentaFamiliaSinFamiliaIdNuncaRecibeDatosNiConsultaLaBase() {
		var sinFamilia = new UsuarioAutenticado(usuarioId, escuelaId, null, Rol.FAMILIA);
		UUID cualquiera = UUID.randomUUID();

		assertError(org.assertj.core.api.Assertions.catchThrowable(() -> servicio.miFamilia(sinFamilia)),
				HttpStatus.NOT_FOUND, "FAMILIA_NO_ENCONTRADA", "La familia no existe.");
		assertError(org.assertj.core.api.Assertions.catchThrowable(() -> servicio.deportista(sinFamilia, cualquiera)),
				HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO", "El deportista no existe.");
		Pagina<DeportistaDeFamilia> lista = servicio.deportistas(sinFamilia, PageRequest.of(0, 20));

		assertThat(lista.contenido()).isEmpty();
		assertThat(lista.totalElementos()).isZero();
		verifyNoInteractions(familias, tutores, vinculos);
	}

	@Test
	void laEscuelaDeLaConsultaEsSiempreLaDelUsuarioNuncaOtra() {
		UUID otraEscuela = UUID.randomUUID();
		var deOtraEscuela = new UsuarioAutenticado(usuarioId, otraEscuela, familiaId, Rol.FAMILIA);
		when(familias.buscarDelPortal(familiaId, otraEscuela)).thenReturn(Optional.empty());
		when(vinculos.deportistaDelPortal(familiaId, otraEscuela, usuarioId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.miFamilia(deOtraEscuela)).isInstanceOf(ExcepcionNegocio.class);
		assertThatThrownBy(() -> servicio.deportista(deOtraEscuela, usuarioId)).isInstanceOf(ExcepcionNegocio.class);

		verify(familias).buscarDelPortal(familiaId, otraEscuela);
		verify(vinculos).deportistaDelPortal(familiaId, otraEscuela, usuarioId);
	}

	// ---------- solo lectura, sin auditoria ----------

	@Test
	void elServicioNoTieneDependenciaDeAuditoriaNiMetodosQueEscriban() {
		assertThat(FamiliaPortalService.class.getDeclaredConstructors()).hasSize(1);
		assertThat(FamiliaPortalService.class.getDeclaredConstructors()[0].getParameterTypes())
				.containsExactly(FamiliaRepository.class, TutorRepository.class, FamiliaDeportistaRepository.class);
		for (Method metodo : FamiliaPortalService.class.getDeclaredMethods()) {
			if (metodo.isSynthetic()) {
				continue;
			}
			Transactional tx = metodo.getAnnotation(Transactional.class);
			assertThat(tx).as("%s debe ser transaccional de solo lectura", metodo.getName()).isNotNull();
			assertThat(tx.readOnly()).as(metodo.getName()).isTrue();
		}
	}

	private static DeportistaDeFamiliaDetalle detalle(UUID id) {
		return new DeportistaDeFamiliaDetalle(id, "Lola", "Gomez", "40111222", null, LocalDate.of(2015, 1, 2), null, null,
				null, null, null, null, null, null, true);
	}
}
