package com.banfieldpatin.backend.familias.vinculos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Configuracion y alcance del lock_timeout de vinculos (sin base de datos): valor por defecto y rango validado, rechazo
 * de usarlo fuera de una transaccion y, sobre todo, que NINGUN otro bean de la aplicacion lo usa (el limite es solo de
 * las transacciones que toman FOR UPDATE, nunca global).
 */
class LockTimeoutVinculosTest {

	@Configuration
	@EnableConfigurationProperties(VinculosPropiedades.class)
	static class Config {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void sinConfigurarElPlazoPorDefectoEsDeTresSegundos() {
		runner.run(ctx -> assertThat(ctx.getBean(VinculosPropiedades.class).lockTimeout()).isEqualTo(Duration.ofSeconds(3)));
	}

	@Test
	void elPlazoSeConfiguraEnBanfieldVinculosLockTimeout() {
		runner.withPropertyValues("banfield.vinculos.lock-timeout=PT0.3S")
				.run(ctx -> assertThat(ctx.getBean(VinculosPropiedades.class).lockTimeout()).isEqualTo(Duration.ofMillis(300)));
		runner.withPropertyValues("banfield.vinculos.lock-timeout=500ms")
				.run(ctx -> assertThat(ctx.getBean(VinculosPropiedades.class).lockTimeout()).isEqualTo(Duration.ofMillis(500)));
	}

	@Test
	void losLimitesDelRangoSonValidos() {
		runner.withPropertyValues("banfield.vinculos.lock-timeout=PT0.1S")
				.run(ctx -> assertThat(ctx).hasNotFailed());
		runner.withPropertyValues("banfield.vinculos.lock-timeout=PT30S").run(ctx -> assertThat(ctx).hasNotFailed());
	}

	@Test
	void unPlazoFueraDeRangoImpideArrancarEnLugarDeDesactivarElLimite() {
		// 0 en PostgreSQL significa "esperar para siempre": exactamente lo que se evita.
		for (String invalido : List.of("PT0S", "PT0.099S", "PT30.001S", "PT5M", "-PT1S")) {
			runner.withPropertyValues("banfield.vinculos.lock-timeout=" + invalido).run(ctx -> {
				assertThat(ctx).as(invalido).hasFailed();
				assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
						"banfield.vinculos.lock-timeout debe estar entre 100 ms y 30 s");
			});
		}
	}

	@Test
	void aplicarFueraDeUnaTransaccionFallaSinTocarLaBase() {
		JdbcClient jdbc = mock(JdbcClient.class);
		LockTimeoutVinculos lockTimeout = new LockTimeoutVinculos(jdbc, new VinculosPropiedades(Duration.ofSeconds(3)));

		assertThatThrownBy(lockTimeout::aplicar).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("transaccion activa");

		verifyNoInteractions(jdbc);
	}

	/** Solo el servicio de vinculos (y el propio componente) dependen de LockTimeoutVinculos: no hay un limite global. */
	@Test
	void ningunOtroBeanDeLaAplicacionUsaElLockTimeout() throws Exception {
		var escaner = new ClassPathScanningCandidateComponentProvider(false);
		escaner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
		List<String> usuarios = new ArrayList<>();
		for (var definicion : escaner.findCandidateComponents("com.banfieldpatin.backend")) {
			Class<?> clase = Class.forName(definicion.getBeanClassName());
			for (Constructor<?> constructor : clase.getDeclaredConstructors()) {
				for (Class<?> tipo : constructor.getParameterTypes()) {
					if (tipo == LockTimeoutVinculos.class) {
						usuarios.add(clase.getSimpleName());
					}
				}
			}
		}
		assertThat(usuarios).containsExactly("VinculoAdminService");
	}
}
