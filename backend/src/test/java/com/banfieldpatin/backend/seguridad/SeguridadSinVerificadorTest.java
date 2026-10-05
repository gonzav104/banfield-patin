package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.banfieldpatin.backend.usuarios.SesionVigenteJdbc;

import tools.jackson.databind.json.JsonMapper;

/**
 * Cableado sin base de datos (leccion de la cadena de autenticacion: un bean opcional abre la puerta): la cadena de
 * seguridad NO arranca si falta el {@link VerificadorSesionVigente}, y arranca con uno. La implementacion de produccion
 * ({@link SesionVigenteJdbc}) se construye con su unico constructor.
 */
class SeguridadSinVerificadorTest {

	@Configuration
	@EnableWebSecurity
	@Import({ SeguridadConfig.class, CookieSesion.class, CookieBearerTokenResolver.class, PuntoEntradaJson.class,
			ManejadorAccesoDenegadoJson.class })
	static class CadenaDeSeguridad {
	}

	private final WebApplicationContextRunner contexto = new WebApplicationContextRunner()
			.withUserConfiguration(CadenaDeSeguridad.class)
			.withBean(Clock.class, Clock::systemUTC)
			.withBean(JsonMapper.class, () -> JsonMapper.builder().build())
			.withBean(JwtDecoder.class, () -> mock(JwtDecoder.class))
			.withBean(JwtAuthenticationConverter.class, JwtAuthenticationConverter::new)
			.withBean(SeguridadPropiedades.class, () -> new SeguridadPropiedades(
					new SeguridadPropiedades.Jwt("dGVzdC1vbmx5LWZpY3RpdGlvdXMtand0LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVm",
							"e", Duration.ofHours(8)),
					new SeguridadPropiedades.Cookie("BP_SESION", false, "Lax"),
					new SeguridadPropiedades.Cors(null),
					new SeguridadPropiedades.Login(5, Duration.ofMinutes(15), Duration.ofMinutes(15), 100),
					new SeguridadPropiedades.Mfa("dGVzdC1vbmx5LWZpY3RpdGlvdXMtbWZhLWtleS0zMmI=", Duration.ofMinutes(5),
							"Banfield Patin")));

	@Test
	void laCadenaNoArrancaSinUnVerificadorDeSesion() {
		contexto.run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class);
			assertThat(rootMessage(ctx.getStartupFailure())).contains(VerificadorSesionVigente.class.getName());
		});
	}

	@Test
	void laCadenaArrancaConUnVerificador() {
		contexto.withBean(VerificadorSesionVigente.class, () -> mock(VerificadorSesionVigente.class)).run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).hasSingleBean(SecurityFilterChain.class);
			assertThat(ctx).hasSingleBean(ManejadorFalloToken.class);
		});
	}

	@Test
	void laImplementacionJdbcSeConstruyeConSuUnicoConstructor() {
		contexto.withBean(JdbcClient.class, () -> mock(JdbcClient.class)).withBean(SesionVigenteJdbc.class).run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).hasSingleBean(VerificadorSesionVigente.class);
			assertThat(ctx.getBean(VerificadorSesionVigente.class)).isInstanceOf(SesionVigenteJdbc.class);
		});
	}

	private static String rootMessage(Throwable t) {
		while (t.getCause() != null) {
			t = t.getCause();
		}
		return String.valueOf(t.getMessage());
	}
}
