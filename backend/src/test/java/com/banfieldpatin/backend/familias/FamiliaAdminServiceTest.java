package com.banfieldpatin.backend.familias;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import com.banfieldpatin.backend.FixturesDominio;
import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.FiltroEstado;
import com.banfieldpatin.backend.familias.dto.FamiliaDetalle;
import com.banfieldpatin.backend.familias.dto.FamiliaSolicitud;
import com.banfieldpatin.backend.familias.tutores.ConteoTutores;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

class FamiliaAdminServiceTest {

	private static final DatosSolicitud DATOS = new DatosSolicitud("203.0.113.9", "JUnit");

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UsuarioAutenticado admin = new UsuarioAutenticado(adminId, escuelaId, null, Rol.ADMIN);
	private final UUID familiaId = UUID.randomUUID();

	private FamiliaRepository familias;
	private TutorRepository tutores;
	private AuditoriaService auditoria;
	private FamiliaAdminService servicio;

	@BeforeEach
	void preparar() {
		familias = mock(FamiliaRepository.class);
		tutores = mock(TutorRepository.class);
		auditoria = mock(AuditoriaService.class);
		servicio = new FamiliaAdminService(familias, tutores, auditoria);
		when(familias.saveAndFlush(any(Familia.class))).thenAnswer(inv -> {
			Familia f = inv.getArgument(0);
			if (f.getId() == null) {
				org.springframework.test.util.ReflectionTestUtils.setField(f, "id", UUID.randomUUID());
			}
			return f;
		});
	}

	private Familia existente(boolean activa) {
		Familia f = FixturesDominio.familia(familiaId, escuelaId, activa);
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId)).thenReturn(Optional.of(f));
		return f;
	}

	private EventoAuditoria unicoEvento() {
		ArgumentCaptor<EventoAuditoria> captor = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrar(captor.capture());
		return captor.getValue();
	}

	// ---------- crear ----------

	@Test
	void crearUsaLaEscuelaYElActorDelTokenYAuditaOrigenAdmin() {
		FamiliaDetalle creada = servicio.crear(admin, new FamiliaSolicitud("  Perez  "), DATOS);

		ArgumentCaptor<Familia> guardada = ArgumentCaptor.forClass(Familia.class);
		verify(familias).saveAndFlush(guardada.capture());
		assertThat(guardada.getValue().getEscuelaId()).isEqualTo(escuelaId);
		assertThat(guardada.getValue().isActiva()).isTrue();
		assertThat(creada.nombreReferencia()).isEqualTo("Perez");
		assertThat(creada.activa()).isTrue();
		assertThat(creada.tutores()).isEmpty();

		EventoAuditoria evento = unicoEvento();
		assertThat(evento.accion()).isEqualTo(AccionAuditoria.FAMILIA_CREADA);
		assertThat(evento.recursoTipo()).isEqualTo("FAMILIA");
		assertThat(evento.recursoId()).isEqualTo(creada.id());
		assertThat(evento.escuelaId()).isEqualTo(escuelaId);
		assertThat(evento.usuarioId()).isEqualTo(adminId);
		assertThat(evento.detalle()).containsOnly(java.util.Map.entry("origen", "ADMIN"));
		assertThat(evento.solicitud()).isEqualTo(DATOS);
	}

	// ---------- lectura ----------

	@Test
	void obtenerDeOtraEscuelaOInexistenteDaElMismo404() {
		when(familias.findByIdAndEscuelaId(any(), any())).thenReturn(Optional.empty());

		for (UUID id : new UUID[] { familiaId, UUID.randomUUID() }) {
			assertThatThrownBy(() -> servicio.obtener(admin, id)).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
				assertThat(e.getEstado()).isEqualTo(HttpStatus.NOT_FOUND);
				assertThat(e.getCodigo()).isEqualTo("FAMILIA_NO_ENCONTRADA");
				assertThat(e.getMessage()).isEqualTo("La familia no existe.");
			});
		}
		verify(familias).findByIdAndEscuelaId(familiaId, escuelaId);
	}

	@Test
	void obtenerDevuelveActivaOInactivaConTutoresVacios() {
		existente(false);

		FamiliaDetalle detalle = servicio.obtener(admin, familiaId);

		assertThat(detalle.activa()).isFalse();
		assertThat(detalle.tutores()).isEmpty();
		verifyNoInteractions(auditoria);
	}

	@Test
	void obtenerDevuelveLosTutoresDeLaFamiliaTambienSiEstaInactiva() {
		existente(false);
		when(tutores.deFamilia(escuelaId, familiaId)).thenReturn(List.of(
				FixturesDominio.tutor(UUID.randomUUID(), escuelaId, familiaId, "Ana", "Perez"),
				FixturesDominio.tutor(UUID.randomUUID(), escuelaId, familiaId, "Luis", "Perez")));

		FamiliaDetalle detalle = servicio.obtener(admin, familiaId);

		assertThat(detalle.activa()).isFalse();
		assertThat(detalle.tutores()).extracting(t -> t.nombre()).containsExactly("Ana", "Luis");
		assertThat(detalle.tutores()).allSatisfy(t -> assertThat(t.familiaId()).isEqualTo(familiaId));
	}

	@Test
	void listarUsaLaEscuelaDelTokenYElNombreDelEstado() {
		Familia f = FixturesDominio.familia(familiaId, escuelaId, true);
		var pageable = PageRequest.of(0, 20);
		when(familias.buscar(escuelaId, "INACTIVOS", "per", pageable)).thenReturn(new PageImpl<>(List.of(f)));

		var pagina = servicio.listar(admin, FiltroEstado.INACTIVOS, "per", pageable);

		assertThat(pagina.contenido()).singleElement().satisfies(r -> {
			assertThat(r.id()).isEqualTo(familiaId);
			assertThat(r.cantidadTutores()).isZero();
			assertThat(r.cantidadDeportistasActivos()).isZero();
		});
		assertThat(pagina.totalElementos()).isEqualTo(1);
	}

	@Test
	void listarCuentaLosTutoresConUnaSolaConsultaAgrupadaParaTodaLaPagina() {
		UUID otra = UUID.randomUUID();
		var pageable = PageRequest.of(0, 20);
		when(familias.buscar(escuelaId, "TODOS", "", pageable)).thenReturn(new PageImpl<>(
				List.of(FixturesDominio.familia(familiaId, escuelaId, true), FixturesDominio.familia(otra, escuelaId, true))));
		when(tutores.contarPorFamilia(eq(escuelaId), any())).thenReturn(List.of(new ConteoTutores(familiaId, 3)));

		var pagina = servicio.listar(admin, FiltroEstado.TODOS, "", pageable);

		assertThat(pagina.contenido()).extracting(r -> r.cantidadTutores()).containsExactly(3L, 0L);
		verify(tutores).contarPorFamilia(eq(escuelaId), eq(List.of(familiaId, otra)));
		verifyNoMoreInteractions(tutores);
	}

	@Test
	void listarUnaPaginaVaciaNoConsultaTutores() {
		var pageable = PageRequest.of(5, 20);
		when(familias.buscar(escuelaId, "TODOS", "", pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

		assertThat(servicio.listar(admin, FiltroEstado.TODOS, "", pageable).contenido()).isEmpty();

		verifyNoInteractions(tutores);
	}

	// ---------- actualizar ----------

	@Test
	void actualizarAuditaSoloCuandoElNombreCambiaConNombresDeCampoSinValores() {
		existente(true);

		FamiliaDetalle detalle = servicio.actualizar(admin, familiaId, new FamiliaSolicitud("Gomez"), DATOS);

		assertThat(detalle.nombreReferencia()).isEqualTo("Gomez");
		EventoAuditoria evento = unicoEvento();
		assertThat(evento.accion()).isEqualTo(AccionAuditoria.FAMILIA_ACTUALIZADA);
		assertThat(evento.detalle()).containsOnly(java.util.Map.entry("camposModificados", List.of("nombreReferencia")));
		assertThat(evento.detalle().toString()).doesNotContain("Gomez");
	}

	@Test
	void actualizarConElMismoNombreNoEscribeNiAudita() {
		existente(true);

		servicio.actualizar(admin, familiaId, new FamiliaSolicitud("Familia Prueba"), DATOS);

		verify(familias, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void actualizarEstaPermitidoSobreUnaFamiliaInactivaYNoCambiaSuEstado() {
		Familia f = existente(false);

		FamiliaDetalle detalle = servicio.actualizar(admin, familiaId, new FamiliaSolicitud("Otro"), DATOS);

		assertThat(detalle.activa()).isFalse();
		assertThat(f.isActiva()).isFalse();
	}

	@Test
	void actualizarDeOtraEscuelaDa404SinEscribir() {
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.actualizar(admin, familiaId, new FamiliaSolicitud("X"), DATOS))
				.isInstanceOfSatisfying(ExcepcionNegocio.class,
						e -> assertThat(e.getCodigo()).isEqualTo("FAMILIA_NO_ENCONTRADA"));
		verify(familias, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	// ---------- activar / desactivar ----------

	@Test
	void desactivarCambiaElEstadoYAuditaUnaVez() {
		Familia f = existente(true);

		FamiliaDetalle detalle = servicio.desactivar(admin, familiaId, DATOS);

		assertThat(detalle.activa()).isFalse();
		assertThat(f.isActiva()).isFalse();
		EventoAuditoria evento = unicoEvento();
		assertThat(evento.accion()).isEqualTo(AccionAuditoria.FAMILIA_DESACTIVADA);
		assertThat(evento.detalle()).isEmpty();
	}

	@Test
	void desactivarEsIdempotenteYNoAuditaLaSegundaVez() {
		existente(true);

		servicio.desactivar(admin, familiaId, DATOS);
		servicio.desactivar(admin, familiaId, DATOS);

		verify(auditoria, org.mockito.Mockito.times(1)).registrar(any());
		verify(familias, org.mockito.Mockito.times(1)).saveAndFlush(any());
	}

	@Test
	void activarEsIdempotenteYAuditaSoloElCambioReal() {
		existente(true);
		servicio.activar(admin, familiaId, DATOS);
		verifyNoInteractions(auditoria);

		Familia inactiva = FixturesDominio.familia(familiaId, escuelaId, false);
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId)).thenReturn(Optional.of(inactiva));
		FamiliaDetalle detalle = servicio.activar(admin, familiaId, DATOS);

		assertThat(detalle.activa()).isTrue();
		assertThat(unicoEvento().accion()).isEqualTo(AccionAuditoria.FAMILIA_ACTIVADA);
	}

	@Test
	void activarYDesactivarNoTienenCascadaSoloTocanElRepositorioDeFamilias() {
		existente(true);

		servicio.desactivar(admin, familiaId, DATOS);

		verify(familias).findByIdAndEscuelaId(familiaId, escuelaId);
		verify(familias).saveAndFlush(any(Familia.class));
		verifyNoMoreInteractions(familias);
		// Solo se LEEN los tutores para armar el detalle: ninguna escritura ni cascada sobre ellos.
		verify(tutores).deFamilia(escuelaId, familiaId);
		verifyNoMoreInteractions(tutores);
	}

	@Test
	void activarYDesactivarDeOtraEscuelaDan404() {
		when(familias.findByIdAndEscuelaId(eq(familiaId), eq(escuelaId))).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.activar(admin, familiaId, DATOS)).isInstanceOf(ExcepcionNegocio.class);
		assertThatThrownBy(() -> servicio.desactivar(admin, familiaId, DATOS)).isInstanceOf(ExcepcionNegocio.class);
		verifyNoInteractions(auditoria);
	}
}
