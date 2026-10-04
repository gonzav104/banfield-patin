package com.banfieldpatin.backend.familias.invitaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import com.banfieldpatin.backend.familias.invitaciones.dto.CrearInvitacionSolicitud;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionCreadaRespuesta;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionRespuesta;
import com.banfieldpatin.backend.familias.invitaciones.dto.NuevaFamiliaSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

class InvitacionAdminServiceTest {

	private static final Instant AHORA = Instant.parse("2026-03-01T12:00:00Z");
	private static final DatosSolicitud DATOS = new DatosSolicitud("203.0.113.9", "JUnit");

	private final UUID escuelaId = UUID.randomUUID();
	private final UsuarioAutenticado admin = new UsuarioAutenticado(UUID.randomUUID(), escuelaId, null, Rol.ADMIN);
	private final UUID familiaId = UUID.randomUUID();

	private InvitacionRepository invitaciones;
	private FamiliaRepository familias;
	private AuditoriaService auditoria;
	private InvitacionAdminService servicio;

	@BeforeEach
	void preparar() {
		invitaciones = mock(InvitacionRepository.class);
		familias = mock(FamiliaRepository.class);
		auditoria = mock(AuditoriaService.class);
		servicio = nuevoServicio(new InvitacionesPropiedades(Duration.ofDays(7), Duration.ofDays(30)),
				"https://app.example.org/");
		when(invitaciones.save(any(Invitacion.class))).thenAnswer(inv -> {
			Invitacion i = inv.getArgument(0);
			ReflectionTestUtils.setField(i, "id", UUID.randomUUID());
			return i;
		});
		when(familias.save(any(Familia.class))).thenAnswer(inv -> {
			Familia f = inv.getArgument(0);
			ReflectionTestUtils.setField(f, "id", UUID.randomUUID());
			return f;
		});
	}

	private InvitacionAdminService nuevoServicio(InvitacionesPropiedades propiedades, String urlBase) {
		return new InvitacionAdminService(invitaciones, familias, auditoria, new GeneradorTokenInvitacion(),
				propiedades, Clock.fixed(AHORA, ZoneOffset.UTC), urlBase);
	}

	private void familiaExistente(boolean activa) {
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId))
				.thenReturn(Optional.of(FixturesDominio.familia(familiaId, escuelaId, activa)));
	}

	private static CrearInvitacionSolicitud conFamilia(UUID id, String email, Integer dias) {
		return new CrearInvitacionSolicitud(id, null, email, dias);
	}

	private Invitacion invitacion(UUID id, Instant expira) {
		return FixturesDominio.invitacion(id, escuelaId, familiaId, admin.id(), AHORA.minus(Duration.ofDays(1)), expira);
	}

	private List<EventoAuditoria> eventos(int esperados) {
		ArgumentCaptor<EventoAuditoria> captor = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria, times(esperados)).registrar(captor.capture());
		return captor.getAllValues();
	}

	// ---------- crear ----------

	@Test
	void creaConFamiliaExistenteGuardandoSoloElHashYAuditando() {
		familiaExistente(true);

		InvitacionCreadaRespuesta r = servicio.crear(admin, conFamilia(familiaId, "Madre@Example.com", null), DATOS);

		ArgumentCaptor<Invitacion> guardada = ArgumentCaptor.forClass(Invitacion.class);
		verify(invitaciones).save(guardada.capture());
		Invitacion i = guardada.getValue();
		assertThat(r.token()).hasSize(43);
		assertThat(i.getTokenHash()).isEqualTo(GeneradorTokenInvitacion.sha256Hex(r.token())).isNotEqualTo(r.token());
		assertThat(i.getEscuelaId()).isEqualTo(escuelaId);
		assertThat(i.getCreadaPor()).isEqualTo(admin.id());
		assertThat(i.getFamiliaId()).isEqualTo(familiaId);
		assertThat(i.getDeportistaId()).isNull();
		assertThat(i.getEmailSugerido()).isEqualTo("madre@example.com");
		assertThat(i.getCreadoEn()).isEqualTo(AHORA);
		assertThat(i.getExpiraEn()).isEqualTo(AHORA.plus(Duration.ofDays(7)));
		assertThat(r.estado()).isEqualTo(EstadoInvitacion.PENDIENTE);
		assertThat(r.enlaceRegistro()).isEqualTo("https://app.example.org/registro/invitacion/" + r.token());
		assertThat(r.familia().id()).isEqualTo(familiaId);

		EventoAuditoria e = eventos(1).get(0);
		assertThat(e.accion()).isEqualTo(AccionAuditoria.INVITACION_CREADA);
		assertThat(e.usuarioId()).isEqualTo(admin.id());
		assertThat(e.escuelaId()).isEqualTo(escuelaId);
		assertThat(e.recursoId()).isEqualTo(r.id());
		assertThat(e.detalle().toString()).doesNotContain(r.token()).doesNotContain(i.getTokenHash());
	}

	@Test
	void sinUrlBaseNoHayEnlaceYElTokenSigueDevolviendoseUnaVez() {
		servicio = nuevoServicio(new InvitacionesPropiedades(Duration.ofDays(7), Duration.ofDays(30)), "");
		familiaExistente(true);

		InvitacionCreadaRespuesta r = servicio.crear(admin, conFamilia(familiaId, null, null), DATOS);

		assertThat(r.enlaceRegistro()).isNull();
		assertThat(r.token()).isNotBlank();
		assertThat(r.toString()).doesNotContain(r.token());
	}

	@Test
	void conNuevaFamiliaLaCreaEnLaMismaOperacionYAudita() {
		CrearInvitacionSolicitud s = new CrearInvitacionSolicitud(null, new NuevaFamiliaSolicitud("  Los Gomez "), null, null);

		InvitacionCreadaRespuesta r = servicio.crear(admin, s, DATOS);

		ArgumentCaptor<Familia> familia = ArgumentCaptor.forClass(Familia.class);
		verify(familias).save(familia.capture());
		assertThat(familia.getValue().getEscuelaId()).isEqualTo(escuelaId);
		assertThat(familia.getValue().getNombreReferencia()).isEqualTo("Los Gomez");
		assertThat(familia.getValue().isActiva()).isTrue();
		assertThat(r.familia().id()).isEqualTo(familia.getValue().getId());
		verify(familias, never()).findByIdAndEscuelaId(any(), any());

		List<EventoAuditoria> auditados = eventos(2);
		assertThat(auditados).extracting(EventoAuditoria::accion)
				.containsExactly(AccionAuditoria.FAMILIA_CREADA, AccionAuditoria.INVITACION_CREADA);
		assertThat(auditados.get(1).detalle()).containsEntry("familiaCreada", true);
	}

	@Test
	void siFallaGuardarLaInvitacionLaExcepcionPropagaYNoSeAudita() {
		when(invitaciones.save(any(Invitacion.class))).thenThrow(new IllegalStateException("fallo simulado"));
		CrearInvitacionSolicitud s = new CrearInvitacionSolicitud(null, new NuevaFamiliaSolicitud("Los Gomez"), null, null);

		// La transaccion de la familia se deshace por la excepcion (rollback declarativo): no hay audit de invitacion.
		assertThatThrownBy(() -> servicio.crear(admin, s, DATOS)).isInstanceOf(IllegalStateException.class);

		ArgumentCaptor<EventoAuditoria> eventos = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria, times(1)).registrar(eventos.capture());
		assertThat(eventos.getAllValues()).extracting(EventoAuditoria::accion)
				.doesNotContain(AccionAuditoria.INVITACION_CREADA);
	}

	@Test
	void ambosOModosDeFamiliaDan400() {
		CrearInvitacionSolicitud ambos = new CrearInvitacionSolicitud(familiaId, new NuevaFamiliaSolicitud("X"), null, null);
		CrearInvitacionSolicitud ninguno = new CrearInvitacionSolicitud(null, null, null, null);

		for (CrearInvitacionSolicitud s : List.of(ambos, ninguno)) {
			assertThatThrownBy(() -> servicio.crear(admin, s, DATOS))
					.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
						assertThat(e.getEstado()).isEqualTo(HttpStatus.BAD_REQUEST);
						assertThat(e.getCodigo()).isEqualTo("VALIDACION");
					});
		}
		verifyNoInteractions(invitaciones, auditoria);
		verify(familias, never()).save(any());
	}

	@Test
	void familiaDeOtraEscuelaOInexistenteDa404SinCrearNada() {
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.crear(admin, conFamilia(familiaId, null, null), DATOS))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
					assertThat(e.getEstado()).isEqualTo(HttpStatus.NOT_FOUND);
					assertThat(e.getCodigo()).isEqualTo("FAMILIA_NO_ENCONTRADA");
				});
		verify(invitaciones, never()).save(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void familiaInactivaDa404IgualQueInexistente() {
		familiaExistente(false);

		assertThatThrownBy(() -> servicio.crear(admin, conFamilia(familiaId, null, null), DATOS))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> assertThat(e.getCodigo()).isEqualTo("FAMILIA_NO_ENCONTRADA"));
		verify(invitaciones, never()).save(any());
	}

	@Test
	void vigenciaSuperiorAlMaximoDa400YOmitidaUsaElDefecto() {
		familiaExistente(true);

		assertThatThrownBy(() -> servicio.crear(admin, conFamilia(familiaId, null, 31), DATOS))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> assertThat(e.getEstado()).isEqualTo(HttpStatus.BAD_REQUEST));
		assertThatThrownBy(() -> servicio.crear(admin, conFamilia(familiaId, null, 0), DATOS))
				.isInstanceOf(ExcepcionNegocio.class);
		servicio.crear(admin, conFamilia(familiaId, null, 30), DATOS);

		ArgumentCaptor<Invitacion> guardada = ArgumentCaptor.forClass(Invitacion.class);
		verify(invitaciones).save(guardada.capture());
		assertThat(guardada.getValue().getExpiraEn()).isEqualTo(AHORA.plus(Duration.ofDays(30)));
	}

	@Test
	void laVigenciaSeConfiguraPorPropiedades() {
		servicio = nuevoServicio(new InvitacionesPropiedades(Duration.ofDays(3), Duration.ofDays(10)), "");
		familiaExistente(true);

		servicio.crear(admin, conFamilia(familiaId, null, null), DATOS);
		assertThatThrownBy(() -> servicio.crear(admin, conFamilia(familiaId, null, 11), DATOS))
				.isInstanceOf(ExcepcionNegocio.class);
		servicio.crear(admin, conFamilia(familiaId, null, 10), DATOS);

		ArgumentCaptor<Invitacion> guardadas = ArgumentCaptor.forClass(Invitacion.class);
		verify(invitaciones, times(2)).save(guardadas.capture());
		assertThat(guardadas.getAllValues()).extracting(Invitacion::getExpiraEn)
				.containsExactly(AHORA.plus(Duration.ofDays(3)), AHORA.plus(Duration.ofDays(10)));
	}

	@Test
	void emailSugeridoEnBlancoSeGuardaComoNull() {
		familiaExistente(true);

		InvitacionCreadaRespuesta r = servicio.crear(admin, conFamilia(familiaId, "   ", null), DATOS);

		assertThat(r.emailSugerido()).isNull();
		ArgumentCaptor<Invitacion> guardada = ArgumentCaptor.forClass(Invitacion.class);
		verify(invitaciones).save(guardada.capture());
		assertThat(guardada.getValue().getEmailSugerido()).isNull();
		assertThat(eventos(1).get(0).detalle()).containsEntry("conEmailSugerido", false);
	}

	// ---------- listar ----------

	@Test
	void listaSoloLaEscuelaDelAdminConElEstadoPedidoYNuncaExponeTokenNiHash() {
		Invitacion i = invitacion(UUID.randomUUID(), AHORA.plus(Duration.ofDays(1)));
		when(invitaciones.listar(eq(escuelaId), eq("PENDIENTE"), eq(AHORA), any()))
				.thenReturn(new PageImpl<>(List.of(i), PageRequest.of(0, 20), 1));
		when(familias.findAllById(anyIterable())).thenReturn(List.of(FixturesDominio.familia(familiaId, escuelaId, true)));

		var pagina = servicio.listar(admin, EstadoInvitacion.PENDIENTE, PageRequest.of(0, 20));

		assertThat(pagina.totalElementos()).isEqualTo(1);
		InvitacionRespuesta r = pagina.contenido().get(0);
		assertThat(r.estado()).isEqualTo(EstadoInvitacion.PENDIENTE);
		assertThat(r.familia().nombreReferencia()).isEqualTo("Familia Prueba");
		assertThat(Arrays.stream(InvitacionRespuesta.class.getRecordComponents()).map(RecordComponent::getName))
				.noneMatch(n -> n.toLowerCase().contains("token") || n.toLowerCase().contains("hash"));
	}

	@Test
	void sinFiltroUsaElCentinelaTodos() {
		when(invitaciones.listar(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

		servicio.listar(admin, null, PageRequest.of(0, 20));

		verify(invitaciones).listar(eq(escuelaId), eq("TODOS"), eq(AHORA), any());
	}

	// ---------- revocar ----------

	@Test
	void revocaUnaPendienteYAudita() {
		UUID id = UUID.randomUUID();
		Invitacion antes = invitacion(id, AHORA.plus(Duration.ofDays(1)));
		Invitacion despues = invitacion(id, AHORA.plus(Duration.ofDays(1)));
		ReflectionTestUtils.setField(despues, "revocadaEn", AHORA);
		ReflectionTestUtils.setField(despues, "revocadaPor", admin.id());
		when(invitaciones.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.of(antes), Optional.of(despues));
		when(invitaciones.marcarRevocada(id, escuelaId, admin.id(), AHORA)).thenReturn(1);
		familiaExistente(true);

		InvitacionRespuesta r = servicio.revocar(admin, id, DATOS);

		assertThat(r.estado()).isEqualTo(EstadoInvitacion.REVOCADA);
		assertThat(r.revocadaEn()).isEqualTo(AHORA);
		EventoAuditoria e = eventos(1).get(0);
		assertThat(e.accion()).isEqualTo(AccionAuditoria.INVITACION_REVOCADA);
		assertThat(e.usuarioId()).isEqualTo(admin.id());
		assertThat(e.recursoId()).isEqualTo(id);
	}

	@Test
	void unaExpiradaSinUsarSePuedeRevocar() {
		UUID id = UUID.randomUUID();
		Invitacion vencida = invitacion(id, AHORA.minusSeconds(1));
		Invitacion despues = invitacion(id, AHORA.minusSeconds(1));
		ReflectionTestUtils.setField(despues, "revocadaEn", AHORA);
		ReflectionTestUtils.setField(despues, "revocadaPor", admin.id());
		when(invitaciones.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.of(vencida), Optional.of(despues));
		when(invitaciones.marcarRevocada(id, escuelaId, admin.id(), AHORA)).thenReturn(1);

		assertThat(servicio.revocar(admin, id, DATOS).estado()).isEqualTo(EstadoInvitacion.REVOCADA);
		verify(invitaciones).marcarRevocada(id, escuelaId, admin.id(), AHORA);
	}

	@Test
	void unaUsadaDa409YNoSeToca() {
		UUID id = UUID.randomUUID();
		Invitacion usada = invitacion(id, AHORA.plus(Duration.ofDays(1)));
		ReflectionTestUtils.setField(usada, "usadoEn", AHORA.minusSeconds(60));
		ReflectionTestUtils.setField(usada, "usuarioId", UUID.randomUUID());
		when(invitaciones.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.of(usada));

		assertThatThrownBy(() -> servicio.revocar(admin, id, DATOS))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
					assertThat(e.getEstado()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(e.getCodigo()).isEqualTo("INVITACION_NO_REVOCABLE");
				});
		verify(invitaciones, never()).marcarRevocada(any(), any(), any(), any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void unaYaRevocadaEsIdempotenteConservaLosDatosOriginalesYNoAudita() {
		UUID id = UUID.randomUUID();
		UUID otroAdmin = UUID.randomUUID();
		Instant original = AHORA.minus(Duration.ofHours(5));
		Invitacion revocada = invitacion(id, AHORA.plus(Duration.ofDays(1)));
		ReflectionTestUtils.setField(revocada, "revocadaEn", original);
		ReflectionTestUtils.setField(revocada, "revocadaPor", otroAdmin);
		when(invitaciones.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.of(revocada));
		familiaExistente(true);

		InvitacionRespuesta r = servicio.revocar(admin, id, DATOS);

		assertThat(r.estado()).isEqualTo(EstadoInvitacion.REVOCADA);
		assertThat(r.revocadaEn()).isEqualTo(original);
		verify(invitaciones, never()).marcarRevocada(any(), any(), any(), any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void idDeOtraEscuelaOInexistenteDa404SinTocarNada() {
		UUID id = UUID.randomUUID();
		when(invitaciones.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.revocar(admin, id, DATOS))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
					assertThat(e.getEstado()).isEqualTo(HttpStatus.NOT_FOUND);
					assertThat(e.getCodigo()).isEqualTo("INVITACION_NO_ENCONTRADA");
				});
		verify(invitaciones).findByIdAndEscuelaId(id, escuelaId);
		verifyNoMoreInteractions(invitaciones);
		verifyNoInteractions(auditoria);
	}

	@Test
	void siAlRevocarLaOtraTransaccionYaLaUsoDa409() {
		UUID id = UUID.randomUUID();
		Invitacion pendiente = invitacion(id, AHORA.plus(Duration.ofDays(1)));
		Invitacion usadaEntretanto = invitacion(id, AHORA.plus(Duration.ofDays(1)));
		ReflectionTestUtils.setField(usadaEntretanto, "usadoEn", AHORA);
		ReflectionTestUtils.setField(usadaEntretanto, "usuarioId", UUID.randomUUID());
		when(invitaciones.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.of(pendiente), Optional.of(usadaEntretanto));
		when(invitaciones.marcarRevocada(id, escuelaId, admin.id(), AHORA)).thenReturn(0);

		assertThatThrownBy(() -> servicio.revocar(admin, id, DATOS))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> assertThat(e.getCodigo()).isEqualTo("INVITACION_NO_REVOCABLE"));
		verifyNoInteractions(auditoria);
	}

	@Test
	void siAlRevocarOtraTransaccionYaLaRevocoRespondeOkSinAuditar() {
		UUID id = UUID.randomUUID();
		Invitacion pendiente = invitacion(id, AHORA.plus(Duration.ofDays(1)));
		Invitacion revocadaEntretanto = invitacion(id, AHORA.plus(Duration.ofDays(1)));
		ReflectionTestUtils.setField(revocadaEntretanto, "revocadaEn", AHORA.minusSeconds(1));
		ReflectionTestUtils.setField(revocadaEntretanto, "revocadaPor", UUID.randomUUID());
		when(invitaciones.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.of(pendiente), Optional.of(revocadaEntretanto));
		when(invitaciones.marcarRevocada(id, escuelaId, admin.id(), AHORA)).thenReturn(0);

		assertThat(servicio.revocar(admin, id, DATOS).estado()).isEqualTo(EstadoInvitacion.REVOCADA);
		verifyNoInteractions(auditoria);
	}

	// ---------- obtener ----------

	@Test
	void obtenerBuscaSiempreDentroDeLaEscuelaDelAdmin() {
		UUID id = UUID.randomUUID();
		when(invitaciones.findByIdAndEscuelaId(id, escuelaId))
				.thenReturn(Optional.of(invitacion(id, AHORA.plus(Duration.ofDays(1)))));
		familiaExistente(true);

		assertThat(servicio.obtener(admin, id).estado()).isEqualTo(EstadoInvitacion.PENDIENTE);
		verify(invitaciones).findByIdAndEscuelaId(id, escuelaId);
	}

	@Test
	void obtenerDeOtraEscuelaDa404() {
		UUID id = UUID.randomUUID();
		when(invitaciones.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.obtener(admin, id))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> assertThat(e.getCodigo()).isEqualTo("INVITACION_NO_ENCONTRADA"));
	}
}
