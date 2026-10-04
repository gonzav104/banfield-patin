package com.banfieldpatin.backend.usuarios.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.banfieldpatin.backend.FixturesDominio;
import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.escuelas.Escuela;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.Usuario;
import com.banfieldpatin.backend.usuarios.UsuarioRepository;

@ExtendWith(OutputCaptureExtension.class)
class BootstrapAdminRunnerTest {

	private static final String PASSWORD = "Secreta-Muy-Larga-123";
	private static final String EMAIL = "Admin@Example.com";
	private static final UUID ESCUELA_ID = UUID.randomUUID();

	private final EscuelaRepository escuelas = mock(EscuelaRepository.class);
	private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
	private final AuditoriaService auditoria = mock(AuditoriaService.class);
	private final PasswordEncoder codificador = PasswordEncoderFactories.createDelegatingPasswordEncoder();
	private Escuela escuela;

	@BeforeEach
	void preparar() {
		escuela = FixturesDominio.escuela(ESCUELA_ID, true); // maxAdministradores = 2
		when(escuelas.findBySlugParaActualizar("escuela-test")).thenReturn(Optional.of(escuela));
		when(usuarios.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));
	}

	private BootstrapAdminRunner runner(String email, String nombre, String apellido, String password) {
		return new BootstrapAdminRunner(new BootstrapAdminPropiedades(true, email, nombre, apellido, password),
				"escuela-test", escuelas, usuarios, auditoria, codificador);
	}

	private BootstrapAdminRunner runnerValido() {
		return runner(EMAIL, "Ana", "Perez", PASSWORD);
	}

	@Test
	void creaAdminConPasswordHasheadaYAuditoria(CapturedOutput salida) {
		when(usuarios.existeEmail(ESCUELA_ID, "admin@example.com")).thenReturn(false);
		when(usuarios.contarAdminsActivos(ESCUELA_ID)).thenReturn(1L);

		runnerValido().run(null);

		ArgumentCaptor<Usuario> guardado = ArgumentCaptor.forClass(Usuario.class);
		verify(usuarios).save(guardado.capture());
		Usuario admin = guardado.getValue();
		assertThat(admin.getRol()).isEqualTo(Rol.ADMIN);
		assertThat(admin.getFamiliaId()).isNull();
		assertThat(admin.getEscuelaId()).isEqualTo(ESCUELA_ID);
		assertThat(admin.getEmail()).isEqualTo("admin@example.com");
		assertThat(admin.isActivo()).isTrue();
		assertThat(admin.isMfaHabilitado()).isFalse();
		assertThat(admin.isEmailVerificado()).isFalse();
		assertThat(admin.getPasswordHash()).startsWith("{bcrypt}").doesNotContain(PASSWORD);
		assertThat(codificador.matches(PASSWORD, admin.getPasswordHash())).isTrue();

		ArgumentCaptor<EventoAuditoria> evento = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrar(evento.capture());
		assertThat(evento.getValue().accion()).isEqualTo(AccionAuditoria.ADMIN_BOOTSTRAP);
		assertThat(evento.getValue().escuelaId()).isEqualTo(ESCUELA_ID);
		assertThat(evento.getValue().detalle().toString()).doesNotContain(PASSWORD).doesNotContain("admin@example.com");

		assertThat(salida.getAll()).doesNotContain(PASSWORD).doesNotContain("admin@example.com");
	}

	@Test
	void emailExistenteNoCambiaNadaNiFalla(CapturedOutput salida) {
		when(usuarios.existeEmail(ESCUELA_ID, "admin@example.com")).thenReturn(true);

		runnerValido().run(null);

		verify(usuarios, never()).save(any());
		verifyNoInteractions(auditoria);
		assertThat(salida.getAll()).doesNotContain(PASSWORD).doesNotContain("admin@example.com");
	}

	@Test
	void noCreaSiSeAlcanzoElMaximoDeAdministradores(CapturedOutput salida) {
		when(usuarios.existeEmail(ESCUELA_ID, "admin@example.com")).thenReturn(false);
		when(usuarios.contarAdminsActivos(ESCUELA_ID)).thenReturn(2L);

		runnerValido().run(null);

		verify(usuarios, never()).save(any());
		verifyNoInteractions(auditoria);
		assertThat(salida.getAll()).contains("maximo de administradores").doesNotContain(PASSWORD);
	}

	@Test
	void fallaSiFaltaUnaPropiedadSinRevelarLaPassword() {
		assertThatThrownBy(() -> runner(EMAIL, " ", "Perez", PASSWORD).run(null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("nombre")
				.hasMessageNotContaining(PASSWORD);
		assertThatThrownBy(() -> runner(EMAIL, "Ana", "Perez", null).run(null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("password");
		verifyNoInteractions(usuarios, auditoria);
	}

	@Test
	void fallaConPasswordDebilSinRevelarla() {
		String debil = "corta";
		assertThatThrownBy(() -> runner(EMAIL, "Ana", "Perez", debil).run(null))
				.isInstanceOf(IllegalStateException.class).hasMessageNotContaining(debil);
		String largaEnBytes = "ñ".repeat(40);
		assertThatThrownBy(() -> runner(EMAIL, "Ana", "Perez", largaEnBytes).run(null))
				.isInstanceOf(IllegalStateException.class).hasMessageNotContaining(largaEnBytes);
		verifyNoInteractions(usuarios, auditoria);
	}

	@Test
	void fallaConEmailInvalido() {
		assertThatThrownBy(() -> runner("no-es-email", "Ana", "Perez", PASSWORD).run(null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("email");
	}

	@Test
	void fallaSiLaEscuelaNoExisteOEstaInactiva() {
		when(escuelas.findBySlugParaActualizar("escuela-test")).thenReturn(Optional.empty());
		assertThatThrownBy(() -> runnerValido().run(null)).isInstanceOf(IllegalStateException.class);

		when(escuelas.findBySlugParaActualizar("escuela-test"))
				.thenReturn(Optional.of(FixturesDominio.escuela(ESCUELA_ID, false)));
		assertThatThrownBy(() -> runnerValido().run(null)).isInstanceOf(IllegalStateException.class);
		verify(usuarios, never()).save(any());
	}

	@Test
	void propiedadesNoExponenSecretosEnToString() {
		String texto = new BootstrapAdminPropiedades(true, EMAIL, "Ana", "Perez", PASSWORD).toString();
		assertThat(texto).doesNotContain(PASSWORD).doesNotContain(EMAIL);
	}

	@Test
	void deshabilitadoPorDefectoNoRegistraElRunner() {
		new ApplicationContextRunner()
				.withBean(EscuelaRepository.class, () -> escuelas)
				.withBean(UsuarioRepository.class, () -> usuarios)
				.withBean(AuditoriaService.class, () -> auditoria)
				.withBean(PasswordEncoder.class, () -> codificador)
				.withPropertyValues("banfield.escuela.slug=escuela-test")
				.withUserConfiguration(BootstrapAdminRunner.class)
				.run(contexto -> assertThat(contexto).doesNotHaveBean(BootstrapAdminRunner.class));
		verifyNoInteractions(usuarios, escuelas, auditoria);
	}

	@Test
	void habilitadoRegistraElRunner() {
		new ApplicationContextRunner()
				.withBean(EscuelaRepository.class, () -> escuelas)
				.withBean(UsuarioRepository.class, () -> usuarios)
				.withBean(AuditoriaService.class, () -> auditoria)
				.withBean(PasswordEncoder.class, () -> codificador)
				.withPropertyValues("banfield.escuela.slug=escuela-test", "banfield.bootstrap-admin.habilitado=true",
						"banfield.bootstrap-admin.email=" + EMAIL, "banfield.bootstrap-admin.nombre=Ana",
						"banfield.bootstrap-admin.apellido=Perez", "banfield.bootstrap-admin.password=" + PASSWORD)
				.withUserConfiguration(BootstrapAdminRunner.class)
				.run(contexto -> assertThat(contexto).hasSingleBean(BootstrapAdminRunner.class));
	}
}
