package com.banfieldpatin.backend.familias.tutores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.banfieldpatin.backend.FixturesDominio;
import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.Familia;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.responsables.CierreVigenciasTutor;
import com.banfieldpatin.backend.familias.tutores.dto.TutorActualizacion;
import com.banfieldpatin.backend.familias.tutores.dto.TutorRespuesta;
import com.banfieldpatin.backend.familias.tutores.dto.TutorSolicitud;
import com.banfieldpatin.backend.familias.vinculos.LockTimeoutVinculos;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

class TutorAdminServiceTest {

	private static final DatosSolicitud DATOS = new DatosSolicitud("203.0.113.9", "JUnit");
	private static final String DNI_DE_PRUEBA = "30111222";

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UsuarioAutenticado admin = new UsuarioAutenticado(adminId, escuelaId, null, Rol.ADMIN);
	private final UUID familiaId = UUID.randomUUID();
	private final UUID tutorId = UUID.randomUUID();

	private TutorRepository tutores;
	private FamiliaRepository familias;
	private AuditoriaService auditoria;
	private EntityManager em;
	private TutorAdminService servicio;
	private CierreVigenciasTutor cierres;
	private LockTimeoutVinculos lockTimeout;

	@BeforeEach
	void preparar() {
		tutores = mock(TutorRepository.class);
		familias = mock(FamiliaRepository.class);
		auditoria = mock(AuditoriaService.class);
		em = mock(EntityManager.class);
		cierres = mock(CierreVigenciasTutor.class);
		lockTimeout = mock(LockTimeoutVinculos.class);
		servicio = new TutorAdminService(tutores, familias, auditoria, em, cierres, lockTimeout);
		when(tutores.saveAndFlush(any(Tutor.class))).thenAnswer(inv -> {
			Tutor t = inv.getArgument(0);
			if (t.getId() == null) {
				ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
			}
			return t;
		});
	}

	private TutorSolicitud solicitud(String dni, String telefono) {
		return new TutorSolicitud("Ana", "Perez", dni, telefono, "ana@example.com", "Madre", 0L);
	}

	/** Reemplazo con las revisiones vigentes de los fixtures (familia 0, tutor 0). */
	private TutorActualizacion actualizacion(String dni, String telefono) {
		return actualizacion(dni, telefono, 0L, 0L);
	}

	private TutorActualizacion actualizacion(String dni, String telefono, Long versionTutor, Long versionFamilia) {
		return new TutorActualizacion("Ana", "Perez", dni, telefono, "ana@example.com", "Madre", versionTutor, versionFamilia);
	}

	private Familia familiaActual;

	private void familia(boolean activa) {
		familia(activa, 0L);
	}

	private void familia(boolean activa, long version) {
		familiaActual = FixturesDominio.familia(familiaId, escuelaId, activa);
		ReflectionTestUtils.setField(familiaActual, "version", version);
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId)).thenReturn(Optional.of(familiaActual));
	}

	private Tutor tutorExistente() {
		return tutorExistente(0L);
	}

	private Tutor tutorExistente(long version) {
		Tutor t = Tutor.crear(escuelaId, familiaId, "Ana", "Perez", DNI_DE_PRUEBA, "11-5555-0000", "ana@example.com",
				"Madre");
		ReflectionTestUtils.setField(t, "id", tutorId);
		ReflectionTestUtils.setField(t, "version", version);
		when(tutores.findByIdAndEscuelaId(tutorId, escuelaId)).thenReturn(Optional.of(t));
		return t;
	}

	private EventoAuditoria unicoEvento() {
		ArgumentCaptor<EventoAuditoria> captor = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrar(captor.capture());
		return captor.getValue();
	}

	private static void assertError(Throwable e, HttpStatus estado, String codigo) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, ex -> {
			assertThat(ex.getEstado()).isEqualTo(estado);
			assertThat(ex.getCodigo()).isEqualTo(codigo);
		});
	}

	// ---------- crear ----------

	@Test
	void crearTomaLaFamiliaDeLaRutaLaEscuelaDelTokenYDejaElTutorActivoSinUsuario() {
		familia(true);

		TutorRespuesta creado = servicio.crear(admin, familiaId, solicitud("30.111.222", "11-5555-0000"), DATOS);

		ArgumentCaptor<Tutor> guardado = ArgumentCaptor.forClass(Tutor.class);
		verify(tutores).saveAndFlush(guardado.capture());
		assertThat(guardado.getValue().getEscuelaId()).isEqualTo(escuelaId);
		assertThat(guardado.getValue().getFamiliaId()).isEqualTo(familiaId);
		assertThat(guardado.getValue().isActivo()).isTrue();
		assertThat(guardado.getValue().getDni()).isEqualTo(DNI_DE_PRUEBA);
		assertThat(creado.familiaId()).isEqualTo(familiaId);
		assertThat(creado.activo()).isTrue();
		assertThat(creado.dni()).isEqualTo(DNI_DE_PRUEBA);

		EventoAuditoria evento = unicoEvento();
		assertThat(evento.accion()).isEqualTo(AccionAuditoria.TUTOR_CREADO);
		assertThat(evento.recursoTipo()).isEqualTo("TUTOR");
		assertThat(evento.recursoId()).isEqualTo(creado.id());
		assertThat(evento.escuelaId()).isEqualTo(escuelaId);
		assertThat(evento.usuarioId()).isEqualTo(adminId);
		assertThat(evento.detalle()).containsOnly(Map.entry("familiaId", familiaId.toString()));
		assertThat(evento.solicitud()).isEqualTo(DATOS);
	}

	@Test
	void crearGuardaYAuditaEnEseOrdenYElDniNoApareceEnLaAuditoria() {
		familia(true);

		servicio.crear(admin, familiaId, solicitud(DNI_DE_PRUEBA, "11-5555-0000"), DATOS);

		InOrder orden = inOrder(em, tutores, auditoria);
		orden.verify(em).lock(familiaActual, LockModeType.PESSIMISTIC_READ);
		orden.verify(tutores).saveAndFlush(any(Tutor.class));
		orden.verify(auditoria).registrar(any());
		assertThat(unicoEvento().detalle().toString()).doesNotContain(DNI_DE_PRUEBA).doesNotContain("11-5555-0000")
				.doesNotContain("ana@example.com");
	}

	@Test
	void crearEnUnaFamiliaAjenaOInexistenteDa404SinCrearNiAuditar() {
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.crear(admin, familiaId, solicitud(null, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "FAMILIA_NO_ENCONTRADA"));

		verify(tutores, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void crearEnUnaFamiliaInactivaDa409SinEscribirNiAuditar() {
		familia(false);

		assertThatThrownBy(() -> servicio.crear(admin, familiaId, solicitud(null, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "FAMILIA_INACTIVA"));

		verify(tutores, never()).saveAndFlush(any());
		verify(tutores, never()).save(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void dosTutoresConElMismoDniSeCreanAmbosSinChequeoDeUnicidad() {
		familia(true);

		TutorRespuesta uno = servicio.crear(admin, familiaId, solicitud(DNI_DE_PRUEBA, null), DATOS);
		TutorRespuesta dos = servicio.crear(admin, familiaId, solicitud(DNI_DE_PRUEBA, null), DATOS);

		assertThat(uno.id()).isNotEqualTo(dos.id());
		assertThat(uno.dni()).isEqualTo(dos.dni()).isEqualTo(DNI_DE_PRUEBA);
		verify(tutores, org.mockito.Mockito.times(2)).saveAndFlush(any(Tutor.class));
		// Solo se consulta la familia y se escribe: ninguna consulta de DNI.
		org.mockito.Mockito.verifyNoMoreInteractions(tutores);
	}

	@Test
	void crearSinDniLoGuardaNulo() {
		familia(true);

		assertThat(servicio.crear(admin, familiaId, solicitud(null, null), DATOS).dni()).isNull();
	}

	// ---------- lectura ----------

	@Test
	void obtenerDeOtraEscuelaOInexistenteDaElMismo404() {
		when(tutores.findByIdAndEscuelaId(any(), any())).thenReturn(Optional.empty());

		for (UUID id : new UUID[] { tutorId, UUID.randomUUID() }) {
			assertThatThrownBy(() -> servicio.obtener(admin, id)).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
				assertThat(e.getEstado()).isEqualTo(HttpStatus.NOT_FOUND);
				assertThat(e.getCodigo()).isEqualTo("TUTOR_NO_ENCONTRADO");
				assertThat(e.getMessage()).isEqualTo("El tutor no existe.");
			});
		}
		verify(tutores).findByIdAndEscuelaId(tutorId, escuelaId);
	}

	@Test
	void obtenerDeUnaFamiliaInactivaEstaPermitidoSinConsultarLaFamilia() {
		tutorExistente();
		familia(false);

		TutorRespuesta r = servicio.obtener(admin, tutorId);

		assertThat(r.id()).isEqualTo(tutorId);
		verifyNoInteractions(familias, auditoria);
	}

	// ---------- actualizar ----------

	@Test
	void actualizarConUnTelefonoNuevoAuditaSoloEsoSinValores() {
		Tutor t = tutorExistente();
		familia(true);

		TutorRespuesta r = servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-6666-1111"), DATOS);

		assertThat(r.telefono()).isEqualTo("11-6666-1111");
		assertThat(t.getFamiliaId()).isEqualTo(familiaId);
		verify(tutores).saveAndFlush(t);
		EventoAuditoria evento = unicoEvento();
		assertThat(evento.accion()).isEqualTo(AccionAuditoria.TUTOR_ACTUALIZADO);
		assertThat(evento.recursoId()).isEqualTo(tutorId);
		assertThat(evento.detalle()).containsOnly(Map.entry("familiaId", familiaId.toString()),
				Map.entry("camposModificados", List.of("telefono")));
		assertThat(evento.detalle().toString()).doesNotContain("11-6666-1111").doesNotContain(DNI_DE_PRUEBA);
	}

	@Test
	void actualizarConLosMismosDatosNoEscribeNiAudita() {
		tutorExistente();
		familia(true);

		servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-5555-0000"), DATOS);

		verify(tutores, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void actualizarConElDniEscritoConPuntosNoCuentaComoCambio() {
		tutorExistente();
		familia(true);

		servicio.actualizar(admin, tutorId, actualizacion("30.111.222", "11-5555-0000"), DATOS);

		verifyNoInteractions(auditoria);
	}

	@Test
	void actualizarPuedeVaciarElDni() {
		Tutor t = tutorExistente();
		familia(true);

		servicio.actualizar(admin, tutorId, actualizacion(null, "11-5555-0000"), DATOS);

		assertThat(t.getDni()).isNull();
		assertThat(unicoEvento().detalle()).containsEntry("camposModificados", List.of("dni"));
	}

	@Test
	void actualizarUnTutorAjenoDa404SinConsultarLaFamiliaNiEscribir() {
		when(tutores.findByIdAndEscuelaId(tutorId, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.actualizar(admin, tutorId, actualizacion(null, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "TUTOR_NO_ENCONTRADO"));

		verifyNoInteractions(familias, auditoria);
		verify(tutores, never()).saveAndFlush(any());
	}

	@Test
	void actualizarEnUnaFamiliaInactivaDa409AunSiLaEdicionEsIdenticaYNoCambiaNada() {
		Tutor t = tutorExistente();
		familia(false);

		// Identica a lo guardado: igual se rechaza (la familia se comprueba ANTES de detectar "sin cambios").
		assertThatThrownBy(() -> servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-5555-0000"), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "FAMILIA_INACTIVA"));
		// Y con un cambio real tampoco escribe.
		assertThatThrownBy(() -> servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-6666-1111"), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "FAMILIA_INACTIVA"));

		assertThat(t.getTelefono()).isEqualTo("11-5555-0000");
		verify(tutores, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void elOrdenDeComprobacionEsTutor404LuegoFamiliaInactivaLuegoEscritura() {
		tutorExistente();
		familia(false);

		assertThatThrownBy(() -> servicio.actualizar(admin, tutorId, actualizacion(null, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "FAMILIA_INACTIVA"));

		InOrder orden = inOrder(tutores, familias);
		orden.verify(tutores).findByIdAndEscuelaId(tutorId, escuelaId);
		orden.verify(familias).findByIdAndEscuelaId(familiaId, escuelaId);
	}

	@Test
	void elMensajeDeFamiliaInactivaNoIncluyeIds() {
		familia(false);

		assertThatThrownBy(() -> servicio.crear(admin, familiaId, solicitud(null, null), DATOS))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> assertThat(e.getMessage())
						.doesNotContain(familiaId.toString()).doesNotContain(escuelaId.toString()));
	}

	// ---------- revisiones (FE-05/U4) ----------

	@Test
	void crearConLaRevisionDeFamiliaViejaDa409EdicionDesactualizadaSinBloquearEscribirNiAuditar() {
		familia(true, 3L);

		assertThatThrownBy(() -> servicio.crear(admin, familiaId, solicitud(DNI_DE_PRUEBA, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA"));

		verify(tutores, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria, em);
	}

	@Test
	void crearBloqueaLaFamiliaConLecturaCompartidaAntesDeEscribirYNoIncrementaSuRevision() {
		familia(true, 3L);

		TutorRespuesta creado = servicio.crear(admin, familiaId,
				new TutorSolicitud("Ana", "Perez", null, null, null, null, 3L), DATOS);

		verify(em).lock(familiaActual, LockModeType.PESSIMISTIC_READ);
		assertThat(familiaActual.getVersion()).isEqualTo(3L); // crear un tutor no cambia la revision de nombre/estado de la familia
		assertThat(creado.version()).isEqualTo("0");
	}

	@Test
	void crearEnUnaFamiliaInactivaDaFamiliaInactivaAunConUnaRevisionVieja() {
		familia(false, 5L);

		assertThatThrownBy(() -> servicio.crear(admin, familiaId, solicitud(null, null), DATOS)) // revision 0, la familia esta en 5
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "FAMILIA_INACTIVA"));

		verifyNoInteractions(auditoria, em);
	}

	@Test
	void crearSinRevisionDeFamiliaDa400SinEscribir() {
		familia(true);

		assertThatThrownBy(() -> servicio.crear(admin, familiaId,
				new TutorSolicitud("Ana", "Perez", null, null, null, null, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.BAD_REQUEST, "VALIDACION"));

		verify(tutores, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void actualizarConLaRevisionDelTutorViejaDa409SinCambiarNadaAunqueLosDatosCoincidan() {
		Tutor t = tutorExistente(4L);
		familia(true, 2L);

		assertThatThrownBy(() -> servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-5555-0000", 3L, 2L), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA"));
		assertThatThrownBy(() -> servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-9999-9999", 3L, 2L), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA"));

		assertThat(t.getTelefono()).isEqualTo("11-5555-0000");
		verify(tutores, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria, em);
	}

	@Test
	void actualizarConLaRevisionDeLaFamiliaViejaDa409AunConLaDelTutorVigente() {
		tutorExistente(4L);
		familia(true, 2L);

		assertThatThrownBy(() -> servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-9999-9999", 4L, 1L), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA"));

		verify(tutores, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria, em);
	}

	@Test
	void actualizarConAmbasRevisionesVigentesBloqueaLaFamiliaYCompruebaElTutorAntesDeEscribir() {
		Tutor t = tutorExistente(4L);
		familia(true, 2L);

		TutorRespuesta r = servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-6666-1111", 4L, 2L), DATOS);

		InOrder orden = inOrder(em, tutores, auditoria);
		orden.verify(em).lock(familiaActual, LockModeType.PESSIMISTIC_READ);
		orden.verify(em).lock(t, LockModeType.OPTIMISTIC);
		orden.verify(tutores).saveAndFlush(t);
		orden.verify(auditoria).registrar(any());
		assertThat(familiaActual.getVersion()).isEqualTo(2L); // la revision de la familia no se toca
		assertThat(r.telefono()).isEqualTo("11-6666-1111");
	}

	@Test
	void unSinCambiosConRevisionesVigentesNoEscribeNiAuditaNiIncrementaPeroSiComprueba() {
		Tutor t = tutorExistente(4L);
		familia(true, 2L);

		TutorRespuesta r = servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-5555-0000", 4L, 2L), DATOS);

		assertThat(r.version()).isEqualTo("4");
		verify(tutores, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
		// La comprobacion se repite al terminar la transaccion: un cambio concurrente tambien invalida un "sin cambios".
		verify(em).lock(familiaActual, LockModeType.PESSIMISTIC_READ);
		verify(em).lock(t, LockModeType.OPTIMISTIC);
	}

	@Test
	void actualizarEnUnaFamiliaInactivaDaFamiliaInactivaAunConRevisionesViejas() {
		tutorExistente(4L);
		familia(false, 2L);

		assertThatThrownBy(() -> servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-9999-9999", 0L, 0L), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "FAMILIA_INACTIVA"));

		verifyNoInteractions(auditoria, em);
	}

	@Test
	void actualizarSinAlgunaDeLasRevisionesDa400SinEscribir() {
		tutorExistente();
		familia(true);

		assertThatThrownBy(() -> servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-9999-9999", null, 0L), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.BAD_REQUEST, "VALIDACION"));
		assertThatThrownBy(() -> servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-9999-9999", 0L, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.BAD_REQUEST, "VALIDACION"));

		verify(tutores, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void elOrdenEsTutor404FamiliaInactivaRevisionDeFamiliaRevisionDelTutorYRecienAhoraElBloqueo() {
		Tutor t = tutorExistente(4L);
		familia(true, 2L);

		servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-6666-1111", 4L, 2L), DATOS);

		InOrder orden = inOrder(tutores, familias, em);
		orden.verify(tutores).findByIdAndEscuelaId(tutorId, escuelaId);
		orden.verify(familias).findByIdAndEscuelaId(familiaId, escuelaId);
		orden.verify(em).lock(familiaActual, LockModeType.PESSIMISTIC_READ);
		orden.verify(em).lock(t, LockModeType.OPTIMISTIC);
	}

	@Test
	void elMensajeDeEdicionDesactualizadaNoIncluyeIdsNiDatosPersonales() {
		tutorExistente(4L);
		familia(true, 2L);

		assertThatThrownBy(() -> servicio.actualizar(admin, tutorId, actualizacion(DNI_DE_PRUEBA, "11-9999-9999", 3L, 2L), DATOS))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> assertThat(e.getMessage())
						.doesNotContain(tutorId.toString()).doesNotContain(familiaId.toString())
						.doesNotContain(escuelaId.toString()).doesNotContain(DNI_DE_PRUEBA).doesNotContain("Ana")
						.doesNotContain("4").doesNotContain("3"));
	}

	@Test
	void desactivarVerificaRevisionYCierraAntesDeGuardarYAuditar() {
		Tutor tutor = tutorExistente(4L);
		assertThat(servicio.desactivar(admin, tutorId, 4L, DATOS).activo()).isFalse();
		InOrder orden = inOrder(em, cierres, tutores, auditoria);
		orden.verify(em).lock(tutor, LockModeType.OPTIMISTIC);
		orden.verify(cierres).deTutor(admin, familiaId, tutorId);
		orden.verify(tutores).saveAndFlush(tutor);
		orden.verify(auditoria).registrar(any(EventoAuditoria.class));
		verifyNoInteractions(familias);
	}

	@Test
	void activarNoTocaVigenciasYNoEscribeAlRepetir() {
		Tutor tutor = tutorExistente(4L);
		tutor.desactivar();
		assertThat(servicio.activar(admin, tutorId, 4L, DATOS).activo()).isTrue();
		assertThat(servicio.activar(admin, tutorId, 4L, DATOS).activo()).isTrue();
		InOrder orden = inOrder(lockTimeout, tutores, auditoria);
		orden.verify(lockTimeout).aplicar();
		orden.verify(tutores).saveAndFlush(tutor);
		orden.verify(auditoria).registrar(any(EventoAuditoria.class));
		verifyNoInteractions(cierres, familias);
	}

	@Test
	void revisionObsoletaNoInvocaElCierreAunqueElTutorYaEsteInactivo() {
		tutorExistente(4L).desactivar();
		assertThatThrownBy(() -> servicio.desactivar(admin, tutorId, 3L, DATOS))
				.isInstanceOfSatisfying(ExcepcionNegocio.class,
						ex -> assertThat(ex.getCodigo()).isEqualTo("EDICION_DESACTUALIZADA"));
		verify(tutores, never()).saveAndFlush(any());
		verifyNoInteractions(cierres, auditoria);
	}
}
