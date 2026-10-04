package com.banfieldpatin.backend.familias.invitaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.escuelas.EscuelaActual;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.seguridad.SeguridadPropiedades;
import com.banfieldpatin.backend.usuarios.UsuarioRepository;

/**
 * Comprueba, sin DataSource, que Spring puede construir los servicios publicos de invitacion: ambos tienen un
 * constructor adicional para tests, asi que el constructor de produccion debe estar marcado explicitamente.
 */
class ServiciosPublicosContextoTest {

	private final ApplicationContextRunner contexto = new ApplicationContextRunner()
			.withBean(Clock.class, Clock::systemUTC)
			.withBean(InvitacionRepository.class, () -> mock(InvitacionRepository.class))
			.withBean(EscuelaRepository.class, () -> mock(EscuelaRepository.class))
			.withBean(FamiliaRepository.class, () -> mock(FamiliaRepository.class))
			.withBean(UsuarioRepository.class, () -> mock(UsuarioRepository.class))
			.withBean(EscuelaActual.class, () -> mock(EscuelaActual.class))
			.withBean(AuditoriaService.class, () -> mock(AuditoriaService.class))
			.withBean(PasswordEncoder.class, PasswordEncoderFactories::createDelegatingPasswordEncoder)
			.withBean(SeguridadPropiedades.class, () -> new SeguridadPropiedades(
					new SeguridadPropiedades.Jwt("dGVzdC1vbmx5LWZpY3RpdGlvdXMtand0LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVm",
							"e", Duration.ofHours(8)),
					new SeguridadPropiedades.Cookie("BP_SESION", false, "Lax"),
					new SeguridadPropiedades.Cors(null),
					new SeguridadPropiedades.Login(5, Duration.ofMinutes(15), Duration.ofMinutes(15), 100),
					new SeguridadPropiedades.Mfa("dGVzdC1vbmx5LWZpY3RpdGlvdXMtbWZhLWtleS0zMmI=", Duration.ofMinutes(5),
							"Banfield Patin")));

	@Test
	void seConstruyenConElConstructorDeProduccion() {
		contexto.withBean(ValidarInvitacionService.class).withBean(RegistroPorInvitacionService.class)
				.run(ctx -> {
					assertThat(ctx).hasNotFailed();
					assertThat(ctx).hasSingleBean(ValidarInvitacionService.class);
					assertThat(ctx).hasSingleBean(RegistroPorInvitacionService.class);
				});
	}

	@Test
	void mfaServiceSeConstruyeConSuConstructorDeProduccion() {
		contexto.withBean(com.banfieldpatin.backend.usuarios.mfa.UsuarioMfaRepository.class,
				() -> mock(com.banfieldpatin.backend.usuarios.mfa.UsuarioMfaRepository.class))
				.withBean(com.banfieldpatin.backend.seguridad.mfa.CifradorSecretoMfa.class)
				.withBean(com.banfieldpatin.backend.usuarios.mfa.MfaService.class)
				.run(ctx -> {
					assertThat(ctx).hasNotFailed();
					assertThat(ctx).hasSingleBean(com.banfieldpatin.backend.usuarios.mfa.MfaService.class);
				});
	}

	@Test
	void unaImplementacionRegistradaReemplazaAlHookPorDefecto() {
		VinculacionPorInvitacion propia = mock(VinculacionPorInvitacion.class);

		contexto.withBean(VinculacionPorInvitacion.class, () -> propia)
				.withBean(RegistroPorInvitacionService.class)
				.run(ctx -> assertThat(ctx).hasNotFailed());
	}
}
