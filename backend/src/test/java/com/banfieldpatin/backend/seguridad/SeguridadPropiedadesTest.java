package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SeguridadPropiedadesTest {

	private static String base64De(int bytes) {
		return Base64.getEncoder().encodeToString(new byte[bytes]);
	}

	private static SeguridadPropiedades enlazar(Map<String, String> props) {
		return new Binder(new MapConfigurationPropertySource(props))
				.bind("banfield.seguridad", SeguridadPropiedades.class).get();
	}

	@Test
	void secretoAusenteFallaSinEcoDelValor() {
		assertThatThrownBy(() -> new SeguridadPropiedades.Jwt(null, "emisor", Duration.ofHours(8)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
		assertThatThrownBy(() -> new SeguridadPropiedades.Jwt("  ", "emisor", Duration.ofHours(8)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void secretoDe16BytesFalla() {
		String secreto = base64De(16);
		assertThatThrownBy(() -> new SeguridadPropiedades.Jwt(secreto, "emisor", Duration.ofHours(8)))
				.isInstanceOf(IllegalStateException.class).hasMessageNotContaining(secreto);
	}

	@Test
	void secretoNoBase64Falla() {
		assertThatThrownBy(() -> new SeguridadPropiedades.Jwt("no es base64 !!", "emisor", Duration.ofHours(8)))
				.isInstanceOf(IllegalStateException.class).hasMessageNotContaining("no es base64 !!");
	}

	@Test
	void secretoDe32BytesOk() {
		var jwt = new SeguridadPropiedades.Jwt(base64De(32), "emisor", Duration.ofHours(8));
		assertThat(jwt.secretoBytes()).hasSize(32);
	}

	@Test
	void duracionFueraDeRangoFalla() {
		String s = base64De(32);
		assertThatThrownBy(() -> new SeguridadPropiedades.Jwt(s, "e", Duration.ofMinutes(4)))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new SeguridadPropiedades.Jwt(s, "e", Duration.ofHours(25)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void sameSiteNoneSinSecureFalla() {
		assertThatThrownBy(() -> new SeguridadPropiedades.Cookie("BP", false, "None"))
				.isInstanceOf(IllegalStateException.class);
		assertThat(new SeguridadPropiedades.Cookie("BP", true, "None").sameSite()).isEqualTo("None");
	}

	@Test
	void sameSiteInvalidoFalla() {
		assertThatThrownBy(() -> new SeguridadPropiedades.Cookie("BP", true, "Foo"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void enlazaDefaultsYCorsVacio() {
		var p = enlazar(Map.of("banfield.seguridad.jwt.secreto", base64De(32),
				"banfield.seguridad.cors.origenes-permitidos", ""));
		assertThat(p.jwt().duracion()).isEqualTo(Duration.ofHours(8));
		assertThat(p.cookie().secure()).isTrue();
		assertThat(p.cookie().sameSite()).isEqualTo("Lax");
		assertThat(p.cors().origenesPermitidos()).isEmpty();
		assertThat(p.login().maxIntentos()).isEqualTo(5);
	}

	@Test
	void enlazarSinSecretoFalla() {
		assertThatThrownBy(() -> enlazar(Map.of("banfield.seguridad.cookie.nombre", "BP")))
				.isInstanceOf(RuntimeException.class);
	}
}
