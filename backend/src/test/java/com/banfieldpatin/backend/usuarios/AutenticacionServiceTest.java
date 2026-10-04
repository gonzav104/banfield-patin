package com.banfieldpatin.backend.usuarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.banfieldpatin.backend.FixturesDominio;
import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.escuelas.Escuela;
import com.banfieldpatin.backend.escuelas.EscuelaActual;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.seguridad.LimitadorIntentosLogin;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;

class AutenticacionServiceTest {

	private static final String PASSWORD = "clave-correcta-123";
	private static final DatosSolicitud SOLICITUD = new DatosSolicitud("10.0.0.1", "JUnit");
	private static final Instant AHORA = Instant.parse("2026-03-01T12:00:00Z");

	private final PasswordEncoder encoder = spy(PasswordEncoderFactories.createDelegatingPasswordEncoder());
	private final EscuelaActual escuelaActual = mock(EscuelaActual.class);
	private final EscuelaRepository escuelas = mock(EscuelaRepository.class);
	private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
	private final FamiliaRepository familias = mock(FamiliaRepository.class);
	private final AuditoriaService auditoria = mock(AuditoriaService.class);
	private final Clock reloj = Clock.fixed(AHORA, ZoneOffset.UTC);
	private final LimitadorIntentosLogin limitador = new LimitadorIntentosLogin(3, Duration.ofMinutes(15),
			Duration.ofMinutes(15), 100, reloj);

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private Escuela escuela;
	private Usuario familiaUsuario;
	private AutenticacionService servicio;

	@BeforeEach
	void preparar() {
		escuela = FixturesDominio.escuela(escuelaId, true);
		familiaUsuario = FixturesDominio.usuario(UUID.randomUUID(), escuelaId, familiaId, Rol.FAMILIA,
				"ana@example.com", encoder.encode(PASSWORD), true);
		when(escuelaActual.obtener()).thenReturn(Optional.of(escuela));
		when(usuarios.buscarPorEmail(escuelaId, "ana@example.com")).thenReturn(Optional.of(familiaUsuario));
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId))
				.thenReturn(Optional.of(FixturesDominio.familia(familiaId, escuelaId, true)));
		servicio = new AutenticacionService(escuelaActual, escuelas, usuarios, familias, encoder, limitador,
				auditoria, reloj);
		clearInvocations(encoder);
	}

	private void assertUniforme(Runnable accion) {
		assertThatThrownBy(accion::run).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
			assertThat(e.getEstado().value()).isEqualTo(401);
			assertThat(e.getCodigo()).isEqualTo("CREDENCIALES_INVALIDAS");
			assertThat(e.getMessage()).isEqualTo("Email o contraseña incorrectos.");
		});
	}

	private EventoAuditoria eventoDeFallo() {
		ArgumentCaptor<EventoAuditoria> c = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrarFallo(c.capture());
		return c.getValue();
	}

	@Test
	void loginFamiliaExitosoNormalizaEmailActualizaAccesoYAudita() {
		UsuarioActualRespuesta r = servicio.autenticar(Rol.FAMILIA, "  Ana@Example.COM ", PASSWORD, SOLICITUD);

		assertThat(r.id()).isEqualTo(familiaUsuario.getId());
		assertThat(r.rol()).isEqualTo(Rol.FAMILIA);
		assertThat(r.familiaId()).isEqualTo(familiaId);
		assertThat(familiaUsuario.getUltimoAccesoEn()).isEqualTo(AHORA);
		ArgumentCaptor<EventoAuditoria> c = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrar(c.capture());
		assertThat(c.getValue().accion()).isEqualTo(AccionAuditoria.LOGIN_EXITOSO);
		assertThat(c.getValue().usuarioId()).isEqualTo(familiaUsuario.getId());
		assertThat(c.getValue().detalle()).containsEntry("canal", "FAMILIA");
	}

	@Test
	void loginAdminExitoso() {
		Usuario admin = FixturesDominio.usuario(UUID.randomUUID(), escuelaId, null, Rol.ADMIN, "admin@example.com",
				encoder.encode(PASSWORD), true);
		when(usuarios.buscarPorEmail(escuelaId, "admin@example.com")).thenReturn(Optional.of(admin));

		UsuarioActualRespuesta r = servicio.autenticar(Rol.ADMIN, "admin@example.com", PASSWORD, SOLICITUD);

		assertThat(r.rol()).isEqualTo(Rol.ADMIN);
		assertThat(r.familiaId()).isNull();
		verifyNoInteractions(familias);
	}

	@Test
	void contrasenaIncorrectaDa401UniformeYAuditaConUsuarioConocido() {
		assertUniforme(() -> servicio.autenticar(Rol.FAMILIA, "ana@example.com", "otra-clave-xxx", SOLICITUD));

		EventoAuditoria e = eventoDeFallo();
		assertThat(e.accion()).isEqualTo(AccionAuditoria.LOGIN_FALLIDO);
		assertThat(e.usuarioId()).isEqualTo(familiaUsuario.getId());
		assertThat(e.detalle()).containsEntry("motivo", "CREDENCIALES");
		verify(auditoria, never()).registrar(any());
	}

	@Test
	void emailDesconocidoEjecutaComparacionFicticiaYAuditaSinUsuarioNiEmailCrudo() {
		assertUniforme(() -> servicio.autenticar(Rol.FAMILIA, "nadie@example.com", PASSWORD, SOLICITUD));

		verify(encoder).matches(anyString(), anyString());
		EventoAuditoria e = eventoDeFallo();
		assertThat(e.usuarioId()).isNull();
		assertThat(e.detalle()).containsEntry("emailEnmascarado", "n***@e***.com");
		assertThat(e.detalle().toString()).doesNotContain("nadie@example.com");
	}

	@Test
	void rolEquivocadoPorEndpointDaLaMismaRespuesta() {
		assertUniforme(() -> servicio.autenticar(Rol.ADMIN, "ana@example.com", PASSWORD, SOLICITUD));
		assertThat(eventoDeFallo().detalle()).containsEntry("motivo", "ROL");
	}

	@Test
	void usuarioInactivoFamiliaInactivaOEscuelaInactivaDan401Uniforme() {
		Usuario inactivo = FixturesDominio.usuario(UUID.randomUUID(), escuelaId, familiaId, Rol.FAMILIA,
				"ana@example.com", encoder.encode(PASSWORD), false);
		when(usuarios.buscarPorEmail(escuelaId, "ana@example.com")).thenReturn(Optional.of(inactivo));
		assertUniforme(() -> servicio.autenticar(Rol.FAMILIA, "ana@example.com", PASSWORD, SOLICITUD));

		when(usuarios.buscarPorEmail(escuelaId, "ana@example.com")).thenReturn(Optional.of(familiaUsuario));
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId))
				.thenReturn(Optional.of(FixturesDominio.familia(familiaId, escuelaId, false)));
		assertUniforme(() -> servicio.autenticar(Rol.FAMILIA, "ana@example.com", PASSWORD, SOLICITUD));

		when(familias.findByIdAndEscuelaId(familiaId, escuelaId))
				.thenReturn(Optional.of(FixturesDominio.familia(familiaId, escuelaId, true)));
		when(escuelaActual.obtener()).thenReturn(Optional.of(FixturesDominio.escuela(escuelaId, false)));
		assertUniforme(() -> servicio.autenticar(Rol.FAMILIA, "ana@example.com", PASSWORD, SOLICITUD));

		assertThat(familiaUsuario.getUltimoAccesoEn()).isNull();
	}

	@Test
	void bloqueoRechazaContrasenaCorrectaIgualQueLasCredencialesInvalidas() {
		for (int i = 0; i < 3; i++) {
			assertUniforme(() -> servicio.autenticar(Rol.FAMILIA, "ana@example.com", "mala-clave-xxx", SOLICITUD));
		}
		clearInvocations(encoder, usuarios, auditoria);

		assertUniforme(() -> servicio.autenticar(Rol.FAMILIA, "ana@example.com", PASSWORD, SOLICITUD));

		verify(encoder).matches(anyString(), anyString());
		verifyNoInteractions(usuarios);
		assertThat(eventoDeFallo().detalle()).containsEntry("motivo", "BLOQUEO");
		assertThat(familiaUsuario.getUltimoAccesoEn()).isNull();
	}

	@Test
	void elExitoReseteaElLimitador() {
		for (int i = 0; i < 2; i++) {
			assertUniforme(() -> servicio.autenticar(Rol.FAMILIA, "ana@example.com", "mala-clave-xxx", SOLICITUD));
		}
		servicio.autenticar(Rol.FAMILIA, "ana@example.com", PASSWORD, SOLICITUD);
		for (int i = 0; i < 2; i++) {
			assertUniforme(() -> servicio.autenticar(Rol.FAMILIA, "ana@example.com", "mala-clave-xxx", SOLICITUD));
		}
		assertThat(limitador.estaBloqueado(LimitadorIntentosLogin.clave("10.0.0.1", "ana@example.com"))).isFalse();
	}

	@Test
	void escuelaConfiguradaInexistenteDa401UniformeSinAuditar() {
		when(escuelaActual.obtener()).thenReturn(Optional.empty());

		assertUniforme(() -> servicio.autenticar(Rol.FAMILIA, "ana@example.com", PASSWORD, SOLICITUD));

		verifyNoInteractions(auditoria, usuarios);
	}

	@Test
	void reHasheaCuandoElEncoderPideActualizar() {
		org.mockito.Mockito.doReturn(true).when(encoder).upgradeEncoding(familiaUsuario.getPasswordHash());
		String hashViejo = familiaUsuario.getPasswordHash();

		servicio.autenticar(Rol.FAMILIA, "ana@example.com", PASSWORD, SOLICITUD);

		assertThat(familiaUsuario.getPasswordHash()).isNotEqualTo(hashViejo).startsWith("{bcrypt}");
		assertThat(encoder.matches(PASSWORD, familiaUsuario.getPasswordHash())).isTrue();
	}

	@Test
	void contrasenaDeMasDe72BytesNoRompeYSeTrataComoIncorrecta() {
		assertUniforme(() -> servicio.autenticar(Rol.FAMILIA, "ana@example.com", "ñ".repeat(100), SOLICITUD));
	}

	@Test
	void actualDevuelveVacioSiSeDesactivoElUsuarioLaFamiliaOLaEscuela() {
		UsuarioAutenticado id = new UsuarioAutenticado(familiaUsuario.getId(), escuelaId, familiaId, Rol.FAMILIA);
		when(usuarios.findByIdAndEscuelaId(id.id(), escuelaId)).thenReturn(Optional.of(familiaUsuario));
		when(escuelas.findById(escuelaId)).thenReturn(Optional.of(escuela));
		assertThat(servicio.actual(id)).isPresent();

		when(escuelas.findById(escuelaId)).thenReturn(Optional.of(FixturesDominio.escuela(escuelaId, false)));
		assertThat(servicio.actual(id)).isEmpty();

		when(escuelas.findById(escuelaId)).thenReturn(Optional.of(escuela));
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId))
				.thenReturn(Optional.of(FixturesDominio.familia(familiaId, escuelaId, false)));
		assertThat(servicio.actual(id)).isEmpty();

		when(usuarios.findByIdAndEscuelaId(id.id(), escuelaId)).thenReturn(Optional.empty());
		assertThat(servicio.actual(id)).isEmpty();
	}

	@Test
	void cerrarSesionAudita() {
		UsuarioAutenticado id = new UsuarioAutenticado(UUID.randomUUID(), escuelaId, familiaId, Rol.FAMILIA);

		servicio.cerrarSesion(id, SOLICITUD);

		ArgumentCaptor<EventoAuditoria> c = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrar(c.capture());
		assertThat(c.getValue().accion()).isEqualTo(AccionAuditoria.LOGOUT);
		assertThat(c.getValue().usuarioId()).isEqualTo(id.id());
		assertThat(c.getValue().detalle()).isEqualTo(Map.of());
	}
}
