package com.banfieldpatin.backend.familias.invitaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.banfieldpatin.backend.FixturesDominio;
import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.escuelas.EscuelaActual;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.invitaciones.dto.RegistroSolicitud;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.Usuario;
import com.banfieldpatin.backend.usuarios.UsuarioRepository;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;

class RegistroPorInvitacionServiceTest {

	private static final Instant AHORA = Instant.parse("2026-03-01T12:00:00Z");
	private static final DatosSolicitud DATOS = new DatosSolicitud("10.0.0.1", "JUnit");
	private static final String PASSWORD = "clave-secreta-123";

	private final InvitacionRepository invitaciones = mock(InvitacionRepository.class);
	private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
	private final EscuelaRepository escuelas = mock(EscuelaRepository.class);
	private final FamiliaRepository familias = mock(FamiliaRepository.class);
	private final EscuelaActual escuelaActual = mock(EscuelaActual.class);
	private final AuditoriaService auditoria = mock(AuditoriaService.class);
	private final VinculacionPorInvitacion hook = mock(VinculacionPorInvitacion.class);
	private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
	private final Clock reloj = Clock.fixed(AHORA, ZoneOffset.UTC);

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final UUID invitacionId = UUID.randomUUID();
	private final UUID usuarioNuevoId = UUID.randomUUID();
	private final String token = new GeneradorTokenInvitacion().generar();
	private Invitacion invitacion;
	private RegistroPorInvitacionService servicio;

	@BeforeEach
	void preparar() {
		invitacion = FixturesDominio.invitacion(invitacionId, escuelaId, familiaId, UUID.randomUUID(),
				AHORA.minusSeconds(3600), AHORA.plusSeconds(3600));
		ReflectionTestUtils.setField(invitacion, "emailSugerido", "sugerido@example.com");
		when(invitaciones.findByTokenHashParaActualizar(GeneradorTokenInvitacion.sha256Hex(token)))
				.thenReturn(Optional.of(invitacion));
		when(escuelas.findById(escuelaId)).thenReturn(Optional.of(FixturesDominio.escuela(escuelaId, true)));
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId))
				.thenReturn(Optional.of(FixturesDominio.familia(familiaId, escuelaId, true)));
		when(escuelaActual.obtener()).thenReturn(Optional.of(FixturesDominio.escuela(escuelaId, true)));
		when(usuarios.existeEmail(any(), any())).thenReturn(false);
		when(usuarios.saveAndFlush(any(Usuario.class))).thenAnswer(inv -> {
			Usuario u = inv.getArgument(0);
			ReflectionTestUtils.setField(u, "id", usuarioNuevoId);
			return u;
		});
		when(invitaciones.marcarUsada(invitacionId, usuarioNuevoId, AHORA)).thenReturn(1);
		servicio = new RegistroPorInvitacionService(invitaciones, usuarios, escuelas, familias, escuelaActual,
				encoder, auditoria, hook, reloj);
	}

	private RegistroSolicitud solicitud(String email) {
		return new RegistroSolicitud(token, "  Ana ", " Perez ", email, PASSWORD);
	}

	private ExcepcionNegocio fallo(RegistroSolicitud s) {
		try {
			servicio.registrar(s, DATOS);
		} catch (ExcepcionNegocio e) {
			return e;
		}
		throw new AssertionError("Se esperaba ExcepcionNegocio");
	}

	private void assertUniforme(ExcepcionNegocio e) {
		assertThat(e.getEstado()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(e.getCodigo()).isEqualTo("INVITACION_NO_DISPONIBLE");
		assertThat(e.getMessage()).isEqualTo(ValidarInvitacionService.invitacionNoDisponible().getMessage());
		verify(usuarios, never()).saveAndFlush(any());
		verify(invitaciones, never()).marcarUsada(any(), any(), any());
		verify(hook, never()).alRegistrar(any(), any());
		verify(auditoria, never()).registrar(any());
	}

	// ---------- exito ----------

	@Test
	void exitoCreaUsuarioFamiliaConDatosDeLaInvitacion() {
		UsuarioActualRespuesta r = servicio.registrar(solicitud("Madre@Example.com "), DATOS);

		ArgumentCaptor<Usuario> guardado = ArgumentCaptor.forClass(Usuario.class);
		verify(usuarios).saveAndFlush(guardado.capture());
		Usuario u = guardado.getValue();
		assertThat(u.getRol()).isEqualTo(Rol.FAMILIA);
		assertThat(u.getFamiliaId()).isEqualTo(familiaId);
		assertThat(u.getEscuelaId()).isEqualTo(escuelaId);
		assertThat(u.getEmail()).isEqualTo("madre@example.com");
		assertThat(u.getNombre()).isEqualTo("Ana");
		assertThat(u.getApellido()).isEqualTo("Perez");
		assertThat(u.isActivo()).isTrue();
		assertThat(u.isEmailVerificado()).isFalse();
		assertThat(u.isMfaHabilitado()).isFalse();
		assertThat(u.getPasswordHash()).startsWith("{bcrypt}").isNotEqualTo(PASSWORD);
		assertThat(encoder.matches(PASSWORD, u.getPasswordHash())).isTrue();

		assertThat(r.id()).isEqualTo(usuarioNuevoId);
		assertThat(r.rol()).isEqualTo(Rol.FAMILIA);
		assertThat(r.escuelaId()).isEqualTo(escuelaId);
		assertThat(r.familiaId()).isEqualTo(familiaId);
	}

	@Test
	void primeroInsertaElUsuarioYLuegoMarcaLaInvitacion() {
		servicio.registrar(solicitud("madre@example.com"), DATOS);

		InOrder orden = inOrder(invitaciones, usuarios, hook, auditoria);
		orden.verify(invitaciones).findByTokenHashParaActualizar(GeneradorTokenInvitacion.sha256Hex(token));
		orden.verify(usuarios).saveAndFlush(any(Usuario.class));
		orden.verify(invitaciones).marcarUsada(invitacionId, usuarioNuevoId, AHORA);
		orden.verify(hook).alRegistrar(eq(invitacion), any(Usuario.class));
		orden.verify(auditoria).registrar(any(EventoAuditoria.class));
	}

	@Test
	void emailDistintoAlSugeridoSeAcepta() {
		UsuarioActualRespuesta r = servicio.registrar(solicitud("otra@example.com"), DATOS);

		assertThat(r.email()).isEqualTo("otra@example.com");
	}

	@Test
	void auditaElRegistroConElUsuarioNuevoYSinTokenNiContrasena() {
		servicio.registrar(solicitud("madre@example.com"), DATOS);

		ArgumentCaptor<EventoAuditoria> evento = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrar(evento.capture());
		EventoAuditoria e = evento.getValue();
		assertThat(e.accion()).isEqualTo(AccionAuditoria.REGISTRO_POR_INVITACION);
		assertThat(e.usuarioId()).isEqualTo(usuarioNuevoId);
		assertThat(e.escuelaId()).isEqualTo(escuelaId);
		assertThat(e.recursoId()).isEqualTo(usuarioNuevoId);
		assertThat(e.detalle().toString()).doesNotContain(token).doesNotContain(PASSWORD)
				.contains(invitacionId.toString());
		assertThat(e.solicitud()).isEqualTo(DATOS);
	}

	@Test
	void elHookSeInvocaUnaVezPorRegistroExitoso() {
		servicio.registrar(solicitud("madre@example.com"), DATOS);

		ArgumentCaptor<Usuario> usuario = ArgumentCaptor.forClass(Usuario.class);
		verify(hook, times(1)).alRegistrar(eq(invitacion), usuario.capture());
		assertThat(usuario.getValue().getId()).isEqualTo(usuarioNuevoId);
	}

	@Test
	void sinOtraImplementacionRegistradaElHookPorDefectoNoHaceNada() {
		var proveedorVacio = new StaticListableBeanFactory().getBeanProvider(VinculacionPorInvitacion.class);
		RegistroPorInvitacionService conDefecto = new RegistroPorInvitacionService(invitaciones, usuarios, escuelas,
				familias, escuelaActual, encoder, auditoria, proveedorVacio, reloj);

		UsuarioActualRespuesta r = conDefecto.registrar(solicitud("madre@example.com"), DATOS);

		assertThat(r.id()).isEqualTo(usuarioNuevoId);
		verify(hook, never()).alRegistrar(any(), any());
	}

	// ---------- invitacion no disponible (error uniforme, sin usuario) ----------

	@Test
	void tokenInexistenteDaErrorUniformeSinCrearUsuario() {
		String otro = new GeneradorTokenInvitacion().generar();
		when(invitaciones.findByTokenHashParaActualizar(GeneradorTokenInvitacion.sha256Hex(otro)))
				.thenReturn(Optional.empty());

		assertUniforme(fallo(new RegistroSolicitud(otro, "Ana", "Perez", "a@example.com", PASSWORD)));
	}

	@Test
	void tokenMalFormadoDaElMismoErrorSinTocarLaBase() {
		ExcepcionNegocio e = fallo(new RegistroSolicitud("corto", "Ana", "Perez", "a@example.com", PASSWORD));

		assertUniforme(e);
		verify(invitaciones, never()).findByTokenHashParaActualizar(any());
	}

	@Test
	void invitacionVencidaRevocadaOUsadaDanElMismoError() {
		ReflectionTestUtils.setField(invitacion, "expiraEn", AHORA);
		assertUniforme(fallo(solicitud("a@example.com")));
		ReflectionTestUtils.setField(invitacion, "expiraEn", AHORA.plusSeconds(3600));

		ReflectionTestUtils.setField(invitacion, "revocadaEn", AHORA.minusSeconds(5));
		assertUniforme(fallo(solicitud("a@example.com")));
		ReflectionTestUtils.setField(invitacion, "revocadaEn", null);

		ReflectionTestUtils.setField(invitacion, "usadoEn", AHORA.minusSeconds(5));
		assertUniforme(fallo(solicitud("a@example.com")));
	}

	@Test
	void escuelaOFamiliaInactivasDanElMismoError() {
		when(escuelas.findById(escuelaId)).thenReturn(Optional.of(FixturesDominio.escuela(escuelaId, false)));
		assertUniforme(fallo(solicitud("a@example.com")));

		when(escuelas.findById(escuelaId)).thenReturn(Optional.of(FixturesDominio.escuela(escuelaId, true)));
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId))
				.thenReturn(Optional.of(FixturesDominio.familia(familiaId, escuelaId, false)));
		assertUniforme(fallo(solicitud("a@example.com")));
	}

	@Test
	void updateCondicionalSinFilasAfectadasNoHayExitoYSeRevierte() {
		when(invitaciones.marcarUsada(invitacionId, usuarioNuevoId, AHORA)).thenReturn(0);

		ExcepcionNegocio e = fallo(solicitud("a@example.com"));

		assertThat(e.getCodigo()).isEqualTo("INVITACION_NO_DISPONIBLE");
		assertThat(e.getEstado()).isEqualTo(HttpStatus.BAD_REQUEST);
		// La excepcion (RuntimeException) revierte la transaccion y con ella el alta del usuario.
		verify(hook, never()).alRegistrar(any(), any());
		verify(auditoria, never()).registrar(any());
	}

	@Test
	void losFallosSeAuditanConMotivoGenericoSinTokenNiEmail() {
		ReflectionTestUtils.setField(invitacion, "usadoEn", AHORA.minusSeconds(5));

		fallo(solicitud("secreto.personal@example.com"));

		ArgumentCaptor<EventoAuditoria> evento = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrarFallo(evento.capture());
		EventoAuditoria e = evento.getValue();
		assertThat(e.accion()).isEqualTo(AccionAuditoria.REGISTRO_FALLIDO);
		assertThat(e.usuarioId()).isNull();
		assertThat(e.detalle()).containsOnlyKeys("motivo");
		assertThat(e.toString()).doesNotContain(token).doesNotContain("secreto.personal").doesNotContain(PASSWORD);
	}

	@Test
	void sinEscuelaConfiguradaElErrorSigueSiendoUniformeYNoSeAudita() {
		when(escuelaActual.obtener()).thenReturn(Optional.empty());
		ReflectionTestUtils.setField(invitacion, "usadoEn", AHORA.minusSeconds(5));

		assertUniforme(fallo(solicitud("a@example.com")));

		verify(auditoria, never()).registrarFallo(any());
	}

	// ---------- email duplicado ----------

	@Test
	void emailDuplicadoDa409SinCrearUsuarioNiConsumirLaInvitacion() {
		when(usuarios.existeEmail(escuelaId, "madre@example.com")).thenReturn(true);

		ExcepcionNegocio e = fallo(solicitud("MADRE@example.com"));

		assertThat(e.getEstado()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(e.getCodigo()).isEqualTo("EMAIL_YA_REGISTRADO");
		verify(usuarios, never()).saveAndFlush(any());
		verify(invitaciones, never()).marcarUsada(any(), any(), any());
		verify(hook, never()).alRegistrar(any(), any());
		ArgumentCaptor<EventoAuditoria> evento = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrarFallo(evento.capture());
		assertThat(evento.getValue().detalle()).containsEntry("motivo", "EMAIL_YA_REGISTRADO");
	}

	@Test
	void violacionDelIndiceUnicoDeEmailPorCarreraDa409() {
		when(usuarios.saveAndFlush(any(Usuario.class))).thenThrow(new DataIntegrityViolationException("x",
				new RuntimeException("duplicate key value violates unique constraint \"uq_usuario_email_escuela\"")));

		ExcepcionNegocio e = fallo(solicitud("madre@example.com"));

		assertThat(e.getEstado()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(e.getCodigo()).isEqualTo("EMAIL_YA_REGISTRADO");
		verify(invitaciones, never()).marcarUsada(any(), any(), any());
	}

	@Test
	void otraViolacionDeIntegridadNoSeDisfrazaDeEmailDuplicado() {
		DataIntegrityViolationException original = new DataIntegrityViolationException("x",
				new RuntimeException("violates foreign key constraint \"fk_usuario_familia_misma_escuela\""));
		when(usuarios.saveAndFlush(any(Usuario.class))).thenThrow(original);

		assertThatThrownBy(() -> servicio.registrar(solicitud("madre@example.com"), DATOS)).isSameAs(original);
		verify(invitaciones, never()).marcarUsada(any(), any(), any());
	}

	// ---------- campos que el cliente no controla ----------

	@Test
	void laSolicitudNoTieneCamposDeRolFamiliaNiEscuela() {
		List<String> campos = java.util.Arrays.stream(RegistroSolicitud.class.getRecordComponents())
				.map(java.lang.reflect.RecordComponent::getName).toList();

		assertThat(campos).containsExactlyInAnyOrder("token", "nombre", "apellido", "email", "password");
	}

	@Test
	void toStringNoExponeTokenNiContrasena() {
		String texto = solicitud("madre@example.com").toString();

		assertThat(texto).doesNotContain(token).doesNotContain(PASSWORD);
	}
}
