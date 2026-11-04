package com.banfieldpatin.backend.documentos.almacen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StorageConfiguracionTest {
	private final ApplicationContextRunner contexto = new ApplicationContextRunner()
			.withUserConfiguration(StorageConfiguracion.class);

	@Test
	void desactivadoPorDefectoNoRequiereSecretosNiCreaCliente() {
		contexto.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(AlmacenDocumentos.class));
		contexto.withInitializer(new ConfigDataApplicationContextInitializer())
				.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(AlmacenDocumentos.class));
	}

	@Test
	void configuracionExternaCreaUnSoloAdaptadorSinTraficoDeArranque() {
		contexto.withPropertyValues("banfield.documentos.storage.habilitado=true",
				"banfield.documentos.storage.url=https://storage.example.invalid",
				"banfield.documentos.storage.bucket=privado",
				"banfield.documentos.storage.credencial=secreto-sintetico",
				"banfield.documentos.storage.conexion=250ms", "banfield.documentos.storage.respuesta=2s")
				.run(ctx -> {
					assertThat(ctx).hasNotFailed().hasSingleBean(AlmacenDocumentos.class);
					var p = ctx.getBean(StoragePropiedades.class);
					assertThat(p.conexion()).isEqualTo(Duration.ofMillis(250));
					assertThat(p.respuesta()).isEqualTo(Duration.ofSeconds(2));
					assertThat(p.toString()).doesNotContain("secreto-sintetico", "example.invalid", "privado");
				});
	}

	@Test
	void habilitarSinConfiguracionFallaCerrado() {
		contexto.withPropertyValues("banfield.documentos.storage.habilitado=true")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {"http://storage.example.invalid", "https://usuario:clave@storage.example.invalid",
			"https://storage.example.invalid?token=secreto", "https://storage.example.invalid/ruta",
			"https://storage.example.invalid#secreto", "no es una URL", "", "file:///tmp/objeto"})
	void origenInseguroOConSecretosNoSeAceptaNiSeRepiteEnElError(String url) {
		assertThatThrownBy(() -> new StoragePropiedades(url, "privado", "secreto-sintetico",
				Duration.ofSeconds(1), Duration.ofSeconds(1)))
				.isInstanceOf(IllegalStateException.class).hasMessage("Configuración de almacenamiento documental inválida")
				.hasNoCause();
	}

	@ParameterizedTest
	@ValueSource(strings = {"../privado", "/privado", "privado?token=x", "nombre personal", ""})
	void bucketEsUnSegmentoSinRutasNiInyeccion(String bucket) {
		assertThatThrownBy(() -> new StoragePropiedades("https://storage.example.invalid", bucket,
				"secreto-sintetico", Duration.ofSeconds(1), Duration.ofSeconds(1)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void secretoYPlazosNoPuedenDesactivarLosControles() {
		for (String secreto : new String[] {"", " ", "token\r\nOtra: cabecera"}) {
			assertThatThrownBy(() -> new StoragePropiedades("https://storage.example.invalid", "privado", secreto,
					Duration.ofSeconds(1), Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
		}
		for (Duration plazo : new Duration[] {Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1), Duration.ofMinutes(6)}) {
			assertThatThrownBy(() -> new StoragePropiedades("https://storage.example.invalid", "privado", "secreto-sintetico",
					plazo, Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> new StoragePropiedades("https://storage.example.invalid", "privado", "secreto-sintetico",
					Duration.ofSeconds(1), plazo)).isInstanceOf(IllegalStateException.class);
		}
	}
}
