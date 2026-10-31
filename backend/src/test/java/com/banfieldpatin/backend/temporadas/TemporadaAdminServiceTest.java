package com.banfieldpatin.backend.temporadas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.dto.TemporadaRespuesta;
import com.banfieldpatin.backend.temporadas.dto.TemporadaSolicitud;
import com.banfieldpatin.backend.usuarios.Rol;

class TemporadaAdminServiceTest {

	private static final DatosSolicitud DATOS = new DatosSolicitud("203.0.113.9", "JUnit");

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UsuarioAutenticado admin = new UsuarioAutenticado(adminId, escuelaId, null, Rol.ADMIN);

	private TemporadaRepository temporadas;
	private EscuelaRepository escuelas;
	private AuditoriaService auditoria;
	private LockTimeoutTemporadas lockTimeout;
	private TemporadaAdminService servicio;

	@BeforeEach
	void preparar() {
		temporadas = mock(TemporadaRepository.class);
		escuelas = mock(EscuelaRepository.class);
		auditoria = mock(AuditoriaService.class);
		lockTimeout = mock(LockTimeoutTemporadas.class);
		servicio = new TemporadaAdminService(temporadas, escuelas, auditoria, lockTimeout);
	}

	private static Temporada temporada(UUID escuela, int anio, boolean activa) {
		Temporada t = Temporada.crear(escuela, anio);
		if (activa) {
			t.activar();
		}
		return t;
	}

	private static void assertError(Throwable e, HttpStatus estado, String codigo) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, n -> {
			assertThat(n.getEstado()).isEqualTo(estado);
			assertThat(n.getCodigo()).isEqualTo(codigo);
		});
	}

	private EventoAuditoria eventoRegistrado() {
		ArgumentCaptor<EventoAuditoria> captor = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrar(captor.capture());
		return captor.getValue();
	}

	// ---------- alta ----------

	@Test
	void crearGuardaUnaTemporadaInactivaDeLaEscuelaDelAdminYAudita() {
		when(temporadas.existsByEscuelaIdAndAnio(escuelaId, (short) 2027)).thenReturn(false);
		when(temporadas.saveAndFlush(any(Temporada.class))).thenAnswer(i -> i.getArgument(0));

		TemporadaRespuesta respuesta = servicio.crear(admin, new TemporadaSolicitud(2027), DATOS);

		ArgumentCaptor<Temporada> guardada = ArgumentCaptor.forClass(Temporada.class);
		verify(temporadas).saveAndFlush(guardada.capture());
		assertThat(guardada.getValue().getEscuelaId()).isEqualTo(escuelaId);
		assertThat(guardada.getValue().isActiva()).isFalse();
		assertThat(respuesta.anio()).isEqualTo(2027);
		assertThat(respuesta.activa()).isFalse();
		EventoAuditoria evento = eventoRegistrado();
		assertThat(evento.accion()).isEqualTo(AccionAuditoria.TEMPORADA_CREADA);
		assertThat(evento.escuelaId()).isEqualTo(escuelaId);
		assertThat(evento.usuarioId()).isEqualTo(adminId);
		assertThat(evento.detalle()).containsEntry("anio", 2027);
	}

	@Test
	void crearUnAnioQueYaExisteDa409SinGuardarNiAuditar() {
		when(temporadas.existsByEscuelaIdAndAnio(escuelaId, (short) 2027)).thenReturn(true);

		assertThatThrownBy(() -> servicio.crear(admin, new TemporadaSolicitud(2027), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "TEMPORADA_ANIO_DUPLICADO"));

		verify(temporadas, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void crearUnaViolacionDeOtraRestriccionSeRelanzaSinMapearAConflicto() {
		when(temporadas.existsByEscuelaIdAndAnio(any(), anyShort())).thenReturn(false);
		DataIntegrityViolationException otra = new DataIntegrityViolationException("otra");
		when(temporadas.saveAndFlush(any(Temporada.class))).thenThrow(otra);

		assertThatThrownBy(() -> servicio.crear(admin, new TemporadaSolicitud(2027), DATOS)).isSameAs(otra);

		verifyNoInteractions(auditoria);
	}

	// ---------- lectura ----------

	@Test
	void obtenerUnaTemporadaAjenaOInexistenteDa404() {
		UUID id = UUID.randomUUID();
		when(temporadas.vistaDe(id, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.obtener(admin, id))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "TEMPORADA_NO_ENCONTRADA"));
	}

	// ---------- activacion ----------

	@Test
	void activarUnaTemporadaAjenaDa404SinBloquearNiEscribir() {
		UUID id = UUID.randomUUID();
		when(temporadas.vistaDe(id, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.activar(admin, id, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "TEMPORADA_NO_ENCONTRADA"));

		verifyNoInteractions(escuelas, auditoria);
		verify(temporadas, never()).saveAndFlush(any());
	}

	@Test
	void activarLaYaActivaDevuelve200SinBloquearEscribirNiAuditar() {
		UUID id = UUID.randomUUID();
		when(temporadas.vistaDe(id, escuelaId)).thenReturn(Optional.of(new TemporadaVista(id, (short) 2027, true)));

		TemporadaRespuesta respuesta = servicio.activar(admin, id, DATOS);

		assertThat(respuesta).isEqualTo(new TemporadaRespuesta(id, 2027, true));
		verifyNoInteractions(escuelas, auditoria);
		verify(temporadas, never()).saveAndFlush(any());
	}

	@Test
	void activarDesactivaLaAnteriorConFlushAntesDeActivarLaNuevaYAuditaConLaAnterior() {
		UUID id = UUID.randomUUID();
		Temporada nueva = temporada(escuelaId, 2027, false);
		Temporada anterior = temporada(escuelaId, 2026, true);
		when(temporadas.vistaDe(id, escuelaId)).thenReturn(Optional.of(new TemporadaVista(id, (short) 2027, false)));
		when(escuelas.bloquearParaActivarTemporada(escuelaId)).thenReturn(Optional.of(escuelaId));
		when(temporadas.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.of(nueva));
		when(temporadas.activaDe(escuelaId)).thenReturn(Optional.of(anterior));
		when(temporadas.saveAndFlush(any(Temporada.class))).thenAnswer(i -> i.getArgument(0));

		TemporadaRespuesta respuesta = servicio.activar(admin, id, DATOS);

		assertThat(respuesta.anio()).isEqualTo(2027);
		assertThat(respuesta.activa()).isTrue();
		assertThat(anterior.isActiva()).isFalse();
		// Orden: lock_timeout -> bloqueo de la escuela -> flush de la anterior desactivada -> flush de la nueva activa.
		InOrder orden = inOrder(lockTimeout, escuelas, temporadas, auditoria);
		orden.verify(lockTimeout).aplicar();
		orden.verify(escuelas).bloquearParaActivarTemporada(escuelaId);
		orden.verify(temporadas).findByIdAndEscuelaId(id, escuelaId);
		orden.verify(temporadas).saveAndFlush(anterior);
		orden.verify(temporadas).saveAndFlush(nueva);
		orden.verify(auditoria).registrar(any(EventoAuditoria.class));
		EventoAuditoria evento = eventoRegistrado();
		assertThat(evento.accion()).isEqualTo(AccionAuditoria.TEMPORADA_ACTIVADA);
		assertThat(evento.detalle()).containsEntry("anio", 2027).containsEntry("anteriorTemporadaId", anterior.getId());
	}

	@Test
	void activarSinActivaPreviaNoDesactivaNadaYLaAnteriorEsNula() {
		UUID id = UUID.randomUUID();
		Temporada nueva = temporada(escuelaId, 2027, false);
		when(temporadas.vistaDe(id, escuelaId)).thenReturn(Optional.of(new TemporadaVista(id, (short) 2027, false)));
		when(escuelas.bloquearParaActivarTemporada(escuelaId)).thenReturn(Optional.of(escuelaId));
		when(temporadas.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.of(nueva));
		when(temporadas.activaDe(escuelaId)).thenReturn(Optional.empty());
		when(temporadas.saveAndFlush(any(Temporada.class))).thenAnswer(i -> i.getArgument(0));

		servicio.activar(admin, id, DATOS);

		verify(temporadas).saveAndFlush(nueva);
		assertThat(eventoRegistrado().detalle()).containsEntry("anteriorTemporadaId", null);
	}

	@Test
	void siTrasElBloqueoYaEstabaActivaDevuelve200SinEscribirNiAuditar() {
		UUID id = UUID.randomUUID();
		Temporada yaActiva = temporada(escuelaId, 2027, true);
		when(temporadas.vistaDe(id, escuelaId)).thenReturn(Optional.of(new TemporadaVista(id, (short) 2027, false)));
		when(escuelas.bloquearParaActivarTemporada(escuelaId)).thenReturn(Optional.of(escuelaId));
		when(temporadas.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.of(yaActiva));

		TemporadaRespuesta respuesta = servicio.activar(admin, id, DATOS);

		assertThat(respuesta.activa()).isTrue();
		verify(temporadas, never()).saveAndFlush(any());
		verify(temporadas, never()).activaDe(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void unaViolacionSinNombreDeRestriccionAlActivarSeRelanzaSinTraducirla() {
		UUID id = UUID.randomUUID();
		Temporada nueva = temporada(escuelaId, 2027, false);
		when(temporadas.vistaDe(id, escuelaId)).thenReturn(Optional.of(new TemporadaVista(id, (short) 2027, false)));
		when(escuelas.bloquearParaActivarTemporada(escuelaId)).thenReturn(Optional.of(escuelaId));
		when(temporadas.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.of(nueva));
		when(temporadas.activaDe(escuelaId)).thenReturn(Optional.empty());
		// Sin restriccion con nombre extraible: se relanza tal cual (500 saneado), nunca se silencia.
		DataIntegrityViolationException sinNombre = new DataIntegrityViolationException("x");
		when(temporadas.saveAndFlush(nueva)).thenThrow(sinNombre);

		assertThatThrownBy(() -> servicio.activar(admin, id, DATOS)).isSameAs(sinNombre);

		verifyNoInteractions(auditoria);
	}

	private static DataIntegrityViolationException violacionDe(String restriccion) {
		return new DataIntegrityViolationException("x", new org.hibernate.exception.ConstraintViolationException("x",
				new java.sql.SQLException("x"), restriccion));
	}

	@Test
	void unaViolacionDelIndiceDeActivaUnicaSeTraduceA409ConcurrenciaYNoAudita() {
		UUID id = UUID.randomUUID();
		Temporada nueva = temporada(escuelaId, 2027, false);
		when(temporadas.vistaDe(id, escuelaId)).thenReturn(Optional.of(new TemporadaVista(id, (short) 2027, false)));
		when(escuelas.bloquearParaActivarTemporada(escuelaId)).thenReturn(Optional.of(escuelaId));
		when(temporadas.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.of(nueva));
		when(temporadas.activaDe(escuelaId)).thenReturn(Optional.empty());
		when(temporadas.saveAndFlush(nueva)).thenThrow(violacionDe("uq_temporada_activa_por_escuela"));

		assertThatThrownBy(() -> servicio.activar(admin, id, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "CONFLICTO_CONCURRENCIA"));

		verifyNoInteractions(auditoria);
	}

	@Test
	void unaCarreraEnElAnioResueltaPorLaBaseDa409AnioDuplicadoSinAuditar() {
		when(temporadas.existsByEscuelaIdAndAnio(escuelaId, (short) 2027)).thenReturn(false);
		when(temporadas.saveAndFlush(any(Temporada.class))).thenThrow(violacionDe("uq_temporada_escuela_anio"));

		assertThatThrownBy(() -> servicio.crear(admin, new TemporadaSolicitud(2027), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "TEMPORADA_ANIO_DUPLICADO"));

		verifyNoInteractions(auditoria);
	}

	@Test
	void unBloqueoQueVenceOUnDeadlockDa409ConcurrenciaConMensajePropioSinEscribirNiAuditar() {
		UUID id = UUID.randomUUID();
		when(temporadas.vistaDe(id, escuelaId)).thenReturn(Optional.of(new TemporadaVista(id, (short) 2027, false)));
		when(escuelas.bloquearParaActivarTemporada(escuelaId)).thenThrow(new CannotAcquireLockException("lock"));

		assertThatThrownBy(() -> servicio.activar(admin, id, DATOS)).satisfies(e -> {
			assertError(e, HttpStatus.CONFLICT, "CONFLICTO_CONCURRENCIA");
			assertThat(e.getMessage()).doesNotContain("deportista").doesNotContain("lock");
		});

		verify(temporadas, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
		verifyNoMoreInteractions(auditoria);
	}

	@Test
	void unaEscuelaInexistenteTrasLaLecturaEsUnErrorInternoNoUnConflicto() {
		UUID id = UUID.randomUUID();
		when(temporadas.vistaDe(id, escuelaId)).thenReturn(Optional.of(new TemporadaVista(id, (short) 2027, false)));
		when(escuelas.bloquearParaActivarTemporada(escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.activar(admin, id, DATOS)).isInstanceOf(IllegalStateException.class);
	}
}
