package com.banfieldpatin.backend.temporadas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.deportistas.Deportista;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaActualizacion;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaRespuesta;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaSolicitud;
import com.banfieldpatin.backend.temporadas.dto.PlantelRespuesta;
import com.banfieldpatin.backend.usuarios.Rol;

class DeportistaTemporadaAdminServiceTest {

	private static final DatosSolicitud DATOS = new DatosSolicitud("203.0.113.9", "JUnit");

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UUID deportistaId = UUID.randomUUID();
	private final UUID temporadaId = UUID.randomUUID();
	private final UsuarioAutenticado admin = new UsuarioAutenticado(adminId, escuelaId, null, Rol.ADMIN);

	private DeportistaTemporadaRepository datos;
	private DeportistaRepository deportistas;
	private TemporadaRepository temporadas;
	private AuditoriaService auditoria;
	private LockTimeoutTemporadas lockTimeout;
	private DeportistaTemporadaAdminService servicio;

	@BeforeEach
	void preparar() {
		datos = mock(DeportistaTemporadaRepository.class);
		deportistas = mock(DeportistaRepository.class);
		temporadas = mock(TemporadaRepository.class);
		auditoria = mock(AuditoriaService.class);
		lockTimeout = mock(LockTimeoutTemporadas.class);
		servicio = new DeportistaTemporadaAdminService(datos, deportistas, temporadas, auditoria, lockTimeout);
	}

	private Deportista deportista(boolean activo) {
		Deportista d = Deportista.crear(escuelaId, "Ana", "Prueba", "10000001", null, LocalDate.of(2012, 1, 1), null, null,
				null, null, null, null, null, null);
		if (!activo) {
			d.desactivar();
		}
		return d;
	}

	private void conDeportistaYTemporada(boolean deportistaActivo) {
		when(deportistas.findByIdAndEscuelaId(deportistaId, escuelaId)).thenReturn(Optional.of(deportista(deportistaActivo)));
		when(temporadas.vistaDe(temporadaId, escuelaId))
				.thenReturn(Optional.of(new TemporadaVista(temporadaId, (short) 2027, false)));
	}

	private DeportistaTemporadaSolicitud solicitud(String numero) {
		return new DeportistaTemporadaSolicitud(temporadaId, "Sub 12", "Femenino", "A", numero, null);
	}

	private static void assertError(Throwable e, HttpStatus estado, String codigo) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, n -> {
			assertThat(n.getEstado()).isEqualTo(estado);
			assertThat(n.getCodigo()).isEqualTo(codigo);
		});
	}

	private static DataIntegrityViolationException violacionDe(String restriccion) {
		return new DataIntegrityViolationException("x", new org.hibernate.exception.ConstraintViolationException("x",
				new java.sql.SQLException("x"), restriccion));
	}

	// ---------- alta ----------

	@Test
	void crearGuardaLaFilaConLaEscuelaDelAdminEnUnaTemporadaInactivaYAuditaSinDatosPersonales() {
		conDeportistaYTemporada(true);
		when(datos.saveAndFlush(any(DeportistaTemporada.class))).thenAnswer(i -> i.getArgument(0));

		DeportistaTemporadaRespuesta respuesta = servicio.crear(admin, deportistaId, solicitud("007"), DATOS);

		ArgumentCaptor<DeportistaTemporada> guardada = ArgumentCaptor.forClass(DeportistaTemporada.class);
		verify(datos).saveAndFlush(guardada.capture());
		assertThat(guardada.getValue().getEscuelaId()).isEqualTo(escuelaId);
		assertThat(guardada.getValue().getDeportistaId()).isEqualTo(deportistaId);
		assertThat(guardada.getValue().getTemporadaId()).isEqualTo(temporadaId);
		assertThat(respuesta.anio()).isEqualTo(2027);
		assertThat(respuesta.numeroCorredor()).isEqualTo("007");
		assertThat(respuesta.estadoDeportivo()).isEqualTo(EstadoDeportivo.ACTIVO);
		ArgumentCaptor<EventoAuditoria> evento = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrar(evento.capture());
		assertThat(evento.getValue().accion()).isEqualTo(AccionAuditoria.DEPORTISTA_TEMPORADA_CREADA);
		assertThat(evento.getValue().escuelaId()).isEqualTo(escuelaId);
		assertThat(evento.getValue().usuarioId()).isEqualTo(adminId);
		assertThat(evento.getValue().detalle()).containsOnlyKeys("temporadaId", "anio")
				.containsEntry("temporadaId", temporadaId).containsEntry("anio", 2027);
	}

	@Test
	void crearParaUnDeportistaAjenoOInexistenteDa404SinTocarNada() {
		when(deportistas.findByIdAndEscuelaId(deportistaId, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.crear(admin, deportistaId, solicitud(null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));

		verify(datos, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void crearEnUnaTemporadaAjenaOInexistenteDa404() {
		when(deportistas.findByIdAndEscuelaId(deportistaId, escuelaId)).thenReturn(Optional.of(deportista(true)));
		when(temporadas.vistaDe(temporadaId, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.crear(admin, deportistaId, solicitud(null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "TEMPORADA_NO_ENCONTRADA"));

		verify(datos, never()).saveAndFlush(any());
	}

	@Test
	void crearParaUnDeportistaInactivoDa409() {
		conDeportistaYTemporada(false);

		assertThatThrownBy(() -> servicio.crear(admin, deportistaId, solicitud(null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "DEPORTISTA_INACTIVO"));

		verify(datos, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void crearUnParQueYaExisteDa409SinGuardarNiAuditar() {
		conDeportistaYTemporada(true);
		when(datos.existsByEscuelaIdAndDeportistaIdAndTemporadaId(escuelaId, deportistaId, temporadaId)).thenReturn(true);

		assertThatThrownBy(() -> servicio.crear(admin, deportistaId, solicitud(null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "DEPORTISTA_TEMPORADA_DUPLICADA"));

		verify(datos, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void crearConUnNumeroYaTomadoEnLaTemporadaDa409PeroSinNumeroNoComprueba() {
		conDeportistaYTemporada(true);
		when(datos.existsByEscuelaIdAndTemporadaIdAndNumeroCorredor(escuelaId, temporadaId, "7")).thenReturn(true);

		assertThatThrownBy(() -> servicio.crear(admin, deportistaId, solicitud("7"), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "NUMERO_CORREDOR_DUPLICADO"));
		verify(datos, never()).saveAndFlush(any());

		when(datos.saveAndFlush(any(DeportistaTemporada.class))).thenAnswer(i -> i.getArgument(0));
		servicio.crear(admin, deportistaId, solicitud(null), DATOS);
		verify(datos, never()).existsByEscuelaIdAndTemporadaIdAndNumeroCorredor(any(), any(), org.mockito.ArgumentMatchers.isNull());
	}

	@Test
	void unaCarreraResueltaPorLaBaseSeTraduceA409SegunLaRestriccionYNoAudita() {
		conDeportistaYTemporada(true);
		when(datos.saveAndFlush(any(DeportistaTemporada.class))).thenThrow(violacionDe("uq_deportista_temporada"));
		assertThatThrownBy(() -> servicio.crear(admin, deportistaId, solicitud(null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "DEPORTISTA_TEMPORADA_DUPLICADA"));

		when(datos.saveAndFlush(any(DeportistaTemporada.class))).thenThrow(violacionDe("uq_numero_corredor_por_temporada"));
		assertThatThrownBy(() -> servicio.crear(admin, deportistaId, solicitud("7"), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "NUMERO_CORREDOR_DUPLICADO"));

		verifyNoInteractions(auditoria);
	}

	@Test
	void unaViolacionDeOtraRestriccionSeRelanzaSinTraducirla() {
		conDeportistaYTemporada(true);
		DataIntegrityViolationException otra = violacionDe("fk_dt_escuela");
		when(datos.saveAndFlush(any(DeportistaTemporada.class))).thenThrow(otra);

		assertThatThrownBy(() -> servicio.crear(admin, deportistaId, solicitud(null), DATOS)).isSameAs(otra);
	}

	// ---------- consulta ----------

	private FilaDeportistaTemporada fila(int anio) {
		return new FilaDeportistaTemporada(UUID.randomUUID(), deportistaId, UUID.randomUUID(), (short) anio, "Sub 12", null,
				null, "007", EstadoDeportivo.ACTIVO);
	}

	@Test
	void elHistorialSeConsultaSoloSiElDeportistaEsDeLaEscuela() {
		when(deportistas.findByIdAndEscuelaId(deportistaId, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.historial(admin, deportistaId))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));
		verify(datos, never()).historial(any(), any());

		when(deportistas.findByIdAndEscuelaId(deportistaId, escuelaId)).thenReturn(Optional.of(deportista(false)));
		when(datos.historial(escuelaId, deportistaId)).thenReturn(List.of(fila(2027), fila(2026)));

		assertThat(servicio.historial(admin, deportistaId)).extracting(DeportistaTemporadaRespuesta::anio)
				.containsExactly(2027, 2026);
	}

	@Test
	void obtenerDevuelveLaFilaEnUnaSolaConsulta() {
		when(datos.fila(escuelaId, deportistaId, temporadaId)).thenReturn(Optional.of(fila(2027)));

		assertThat(servicio.obtener(admin, deportistaId, temporadaId).anio()).isEqualTo(2027);

		verifyNoInteractions(deportistas, temporadas);
	}

	@Test
	void obtenerDistingueQueFaltaElDeportistaLaTemporadaOLaFila() {
		when(datos.fila(escuelaId, deportistaId, temporadaId)).thenReturn(Optional.empty());
		when(deportistas.findByIdAndEscuelaId(deportistaId, escuelaId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> servicio.obtener(admin, deportistaId, temporadaId))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));

		when(deportistas.findByIdAndEscuelaId(deportistaId, escuelaId)).thenReturn(Optional.of(deportista(true)));
		when(temporadas.vistaDe(temporadaId, escuelaId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> servicio.obtener(admin, deportistaId, temporadaId))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "TEMPORADA_NO_ENCONTRADA"));

		when(temporadas.vistaDe(temporadaId, escuelaId))
				.thenReturn(Optional.of(new TemporadaVista(temporadaId, (short) 2027, true)));
		assertThatThrownBy(() -> servicio.obtener(admin, deportistaId, temporadaId))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_TEMPORADA_NO_ENCONTRADA"));
	}

	// ---------- plantel ----------

	@Test
	void elPlantelDeUnaTemporadaAjenaDa404() {
		when(temporadas.vistaDe(temporadaId, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.plantel(admin, temporadaId, null, PageRequest.of(0, 20)))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "TEMPORADA_NO_ENCONTRADA"));
	}

	@Test
	void elPlantelSinFiltroLoPideCompletoYConFiltroConvierteElEstado() {
		when(temporadas.vistaDe(temporadaId, escuelaId))
				.thenReturn(Optional.of(new TemporadaVista(temporadaId, (short) 2027, true)));
		FilaPlantel unaFila = new FilaPlantel(UUID.randomUUID(), deportistaId, temporadaId, "Ana", "Prueba", null, null, null,
				null, EstadoDeportivo.ACTIVO);
		when(datos.plantel(any(), any(), anyString(), any(), any())).thenReturn(new PageImpl<>(List.of(unaFila)));

		List<PlantelRespuesta> completo = servicio.plantel(admin, temporadaId, "  ", PageRequest.of(0, 20)).contenido();
		verify(datos).plantel(escuelaId, temporadaId, "TODOS", EstadoDeportivo.ACTIVO, PageRequest.of(0, 20));
		assertThat(completo).extracting(PlantelRespuesta::apellido).containsExactly("Prueba");

		servicio.plantel(admin, temporadaId, "SUSPENDIDO", PageRequest.of(0, 20));
		verify(datos).plantel(escuelaId, temporadaId, "SUSPENDIDO", EstadoDeportivo.SUSPENDIDO, PageRequest.of(0, 20));
	}

	@Test
	void unFiltroDeEstadoDesconocidoDa400SolicitudInvalida() {
		when(temporadas.vistaDe(temporadaId, escuelaId))
				.thenReturn(Optional.of(new TemporadaVista(temporadaId, (short) 2027, true)));

		assertThatThrownBy(() -> servicio.plantel(admin, temporadaId, "x", PageRequest.of(0, 20)))
				.satisfies(e -> assertError(e, HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA"));
	}

	// ---------- edicion ----------

	private DeportistaTemporada filaEntidad() {
		return DeportistaTemporada.crear(escuelaId, deportistaId, temporadaId, "Sub 12", "Femenino", "A", "007",
				EstadoDeportivo.ACTIVO);
	}

	private DeportistaTemporadaActualizacion cambio(String categoria, String numero, EstadoDeportivo estado) {
		return new DeportistaTemporadaActualizacion(categoria, "Femenino", "A", numero, estado);
	}

	private void conFilaBloqueada(DeportistaTemporada fila) {
		when(datos.bloquear(escuelaId, deportistaId, temporadaId)).thenReturn(Optional.of(UUID.randomUUID()));
		when(datos.findByEscuelaIdAndDeportistaIdAndTemporadaId(escuelaId, deportistaId, temporadaId))
				.thenReturn(Optional.of(fila));
		when(temporadas.vistaDe(temporadaId, escuelaId))
				.thenReturn(Optional.of(new TemporadaVista(temporadaId, (short) 2027, false)));
		when(datos.saveAndFlush(any(DeportistaTemporada.class))).thenAnswer(i -> i.getArgument(0));
	}

	@Test
	void actualizarBloqueaLaFilaLaVuelveALeerGuardaYAuditaSoloNombresDeCamposYElCambioDeEstado() {
		DeportistaTemporada fila = filaEntidad();
		conFilaBloqueada(fila);

		DeportistaTemporadaRespuesta r = servicio.actualizar(admin, deportistaId, temporadaId,
				cambio("Sub 14", "007", EstadoDeportivo.SUSPENDIDO), DATOS);

		assertThat(r.categoria()).isEqualTo("Sub 14");
		assertThat(r.estadoDeportivo()).isEqualTo(EstadoDeportivo.SUSPENDIDO);
		assertThat(r.anio()).isEqualTo(2027);
		org.mockito.InOrder orden = org.mockito.Mockito.inOrder(lockTimeout, datos, auditoria);
		orden.verify(lockTimeout).aplicar();
		orden.verify(datos).bloquear(escuelaId, deportistaId, temporadaId);
		orden.verify(datos).findByEscuelaIdAndDeportistaIdAndTemporadaId(escuelaId, deportistaId, temporadaId);
		orden.verify(datos).saveAndFlush(fila);
		orden.verify(auditoria).registrar(any(EventoAuditoria.class));
		ArgumentCaptor<EventoAuditoria> evento = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrar(evento.capture());
		assertThat(evento.getValue().accion()).isEqualTo(AccionAuditoria.DEPORTISTA_TEMPORADA_ACTUALIZADA);
		assertThat(evento.getValue().recursoId()).isEqualTo(fila.getId());
		assertThat(evento.getValue().detalle()).containsOnlyKeys("temporadaId", "camposModificados", "estadoAnterior",
				"estadoNuevo").containsEntry("camposModificados", List.of("categoria", "estadoDeportivo"))
				.containsEntry("estadoAnterior", "ACTIVO").containsEntry("estadoNuevo", "SUSPENDIDO");
		assertThat(evento.getValue().detalle().toString()).doesNotContain("Sub 14", "007", "Femenino");
	}

	@Test
	void actualizarSinCambiosRealesDevuelveLaFilaSinGuardarNiAuditar() {
		conFilaBloqueada(filaEntidad());

		DeportistaTemporadaRespuesta r = servicio.actualizar(admin, deportistaId, temporadaId,
				cambio("Sub 12", "007", EstadoDeportivo.ACTIVO), DATOS);

		assertThat(r.categoria()).isEqualTo("Sub 12");
		verify(datos, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void unCambioQueNoTocaElEstadoNoLoAgregaALaAuditoria() {
		conFilaBloqueada(filaEntidad());

		servicio.actualizar(admin, deportistaId, temporadaId, cambio("Sub 14", "007", EstadoDeportivo.ACTIVO), DATOS);

		ArgumentCaptor<EventoAuditoria> evento = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrar(evento.capture());
		assertThat(evento.getValue().detalle()).containsOnlyKeys("temporadaId", "camposModificados");
	}

	@Test
	void actualizarUnNumeroTomadoPorOtraFilaDa409YConservarElPropioNoEsConflicto() {
		conFilaBloqueada(filaEntidad());
		when(datos.existsByEscuelaIdAndTemporadaIdAndNumeroCorredorAndIdNot(any(), any(), any(), any())).thenReturn(true);

		assertThatThrownBy(() -> servicio.actualizar(admin, deportistaId, temporadaId,
				cambio("Sub 12", "008", EstadoDeportivo.ACTIVO), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "NUMERO_CORREDOR_DUPLICADO"));
		verify(datos, never()).saveAndFlush(any());

		// Mismo numero que ya tiene (007) y quitarlo (null): no se consulta unicidad.
		servicio.actualizar(admin, deportistaId, temporadaId, cambio("Sub 12", "007", EstadoDeportivo.ACTIVO), DATOS);
		servicio.actualizar(admin, deportistaId, temporadaId, cambio("Sub 12", null, EstadoDeportivo.ACTIVO), DATOS);
		verify(datos, org.mockito.Mockito.times(1)).existsByEscuelaIdAndTemporadaIdAndNumeroCorredorAndIdNot(any(), any(),
				any(), any());
	}

	@Test
	void actualizarUnaFilaInexistenteDistingueDeportistaTemporadaOFila() {
		when(datos.bloquear(escuelaId, deportistaId, temporadaId)).thenReturn(Optional.empty());
		when(deportistas.findByIdAndEscuelaId(deportistaId, escuelaId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> servicio.actualizar(admin, deportistaId, temporadaId,
				cambio(null, null, EstadoDeportivo.ACTIVO), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));

		when(deportistas.findByIdAndEscuelaId(deportistaId, escuelaId)).thenReturn(Optional.of(deportista(true)));
		when(temporadas.vistaDe(temporadaId, escuelaId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> servicio.actualizar(admin, deportistaId, temporadaId,
				cambio(null, null, EstadoDeportivo.ACTIVO), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "TEMPORADA_NO_ENCONTRADA"));

		when(temporadas.vistaDe(temporadaId, escuelaId))
				.thenReturn(Optional.of(new TemporadaVista(temporadaId, (short) 2027, false)));
		assertThatThrownBy(() -> servicio.actualizar(admin, deportistaId, temporadaId,
				cambio(null, null, EstadoDeportivo.ACTIVO), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_TEMPORADA_NO_ENCONTRADA"));
		verify(datos, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void editarSigueSiendoPosibleConElDeportistaInactivoYEnUnaTemporadaInactiva() {
		conFilaBloqueada(filaEntidad());
		when(deportistas.findByIdAndEscuelaId(deportistaId, escuelaId)).thenReturn(Optional.of(deportista(false)));

		servicio.actualizar(admin, deportistaId, temporadaId, cambio("Sub 14", "007", EstadoDeportivo.INACTIVO), DATOS);

		verify(datos).saveAndFlush(any(DeportistaTemporada.class));
	}

	@Test
	void unaCarreraEnElNumeroResueltaPorLaBaseDa409YUnBloqueoQueVenceDa409ConMensajePropio() {
		conFilaBloqueada(filaEntidad());
		when(datos.saveAndFlush(any(DeportistaTemporada.class))).thenThrow(violacionDe("uq_numero_corredor_por_temporada"));
		assertThatThrownBy(() -> servicio.actualizar(admin, deportistaId, temporadaId,
				cambio("Sub 12", "008", EstadoDeportivo.ACTIVO), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "NUMERO_CORREDOR_DUPLICADO"));

		when(datos.bloquear(escuelaId, deportistaId, temporadaId)).thenThrow(new org.springframework.dao.CannotAcquireLockException("l"));
		assertThatThrownBy(() -> servicio.actualizar(admin, deportistaId, temporadaId,
				cambio("Sub 12", "007", EstadoDeportivo.ACTIVO), DATOS)).satisfies(e -> {
					assertError(e, HttpStatus.CONFLICT, "CONFLICTO_CONCURRENCIA");
					assertThat(e.getMessage()).doesNotContain("deportista ", "activacion").doesNotContain("lock");
				});
		verifyNoInteractions(auditoria);
	}
}

