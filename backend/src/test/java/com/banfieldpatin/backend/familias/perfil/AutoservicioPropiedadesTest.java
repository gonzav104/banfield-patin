package com.banfieldpatin.backend.familias.perfil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Banderas apagadas y tope 10 por defecto; un tope fuera de 1..100 impide arrancar (no se aplica en silencio). */
class AutoservicioPropiedadesTest {

	@Configuration
	@EnableConfigurationProperties(AutoservicioPropiedades.class)
	static class Config {
	}

	private final ApplicationContextRunner contexto = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of()).withUserConfiguration(Config.class);

	@Test
	void porDefectoTodoApagadoYTopeDiez() {
		contexto.run(c -> {
			AutoservicioPropiedades p = c.getBean(AutoservicioPropiedades.class);
			assertThat(p.perfilHabilitado()).isFalse();
			assertThat(p.tutoresHabilitado()).isFalse();
			assertThat(p.topeTutoresPorFamilia()).isEqualTo(10);
		});
	}

	@Test
	void elTopeSeConfiguraYFueraDeRangoNoArranca() {
		contexto.withPropertyValues("banfield.autoservicio.tope-tutores-por-familia=4")
				.run(c -> assertThat(c.getBean(AutoservicioPropiedades.class).topeTutoresPorFamilia()).isEqualTo(4));
		for (String invalido : new String[] { "0", "101", "-1" }) {
			contexto.withPropertyValues("banfield.autoservicio.tope-tutores-por-familia=" + invalido)
					.run(c -> assertThat(c).hasFailed());
		}
		assertThatThrownBy(() -> new AutoservicioPropiedades(false, false, 0)).isInstanceOf(IllegalArgumentException.class);
	}
}
