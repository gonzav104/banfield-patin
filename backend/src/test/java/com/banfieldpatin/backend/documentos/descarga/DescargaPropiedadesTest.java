package com.banfieldpatin.backend.documentos.descarga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DescargaPropiedadesTest {
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(DescargaPropiedades.class)
	static class Propiedades {
	}

	private final ApplicationContextRunner contexto = new ApplicationContextRunner().withUserConfiguration(Propiedades.class);

	@Test
	void porDefectoAdmiteDosDescargasSimultaneas() {
		contexto.run(ctx -> assertThat(ctx.getBean(DescargaPropiedades.class).concurrencia()).isEqualTo(2));
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 8 })
	void aceptaElRangoDeUnoAOcho(int valor) {
		contexto.withPropertyValues("banfield.documentos.descarga.concurrencia=" + valor)
				.run(ctx -> assertThat(ctx.getBean(DescargaPropiedades.class).concurrencia()).isEqualTo(valor));
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1, 9, 64 })
	void fueraDeRangoNoArranca(int valor) {
		contexto.withPropertyValues("banfield.documentos.descarga.concurrencia=" + valor)
				.run(ctx -> assertThat(ctx).hasFailed());
		assertThatThrownBy(() -> new DescargaPropiedades(valor)).isInstanceOf(IllegalStateException.class);
	}
}
