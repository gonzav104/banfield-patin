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
				"banfield.seguridad.mfa.clave-cifrado", base64De(32),
				"banfield.seguridad.cors.origenes-permitidos", ""));
		assertThat(p.jwt().duracion()).isEqualTo(Duration.ofHours(8));
		assertThat(p.cookie().secure()).isTrue();
		assertThat(p.cookie().sameSite()).isEqualTo("Lax");
		assertThat(p.cors().origenesPermitidos()).isEmpty();
		assertThat(p.login().maxIntentos()).isEqualTo(5);
		assertThat(p.mfa().duracionPendiente()).isEqualTo(Duration.ofMinutes(5));
		assertThat(p.mfa().emisor()).isEqualTo("Banfield Patin");
	}

	@Test
	void enlazarSinSecretoFalla() {
		assertThatThrownBy(() -> enlazar(Map.of("banfield.seguridad.cookie.nombre", "BP",
				"banfield.seguridad.mfa.clave-cifrado", base64De(32))))
				.isInstanceOf(RuntimeException.class);
	}

	// ---------- MFA ----------

	@Test
	void enlazarSinClaveMfaFalla() {
		assertThatThrownBy(() -> enlazar(Map.of("banfield.seguridad.jwt.secreto", base64De(32))))
				.isInstanceOf(RuntimeException.class)
				.hasStackTraceContaining("MFA_CLAVE_CIFRADO");
	}

	@Test
	void claveMfaAusenteOEnBlancoFallaSinEco() {
		assertThatThrownBy(() -> new SeguridadPropiedades.Mfa(null, Duration.ofMinutes(5), "Banfield"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("MFA_CLAVE_CIFRADO");
		assertThatThrownBy(() -> new SeguridadPropiedades.Mfa("  ", Duration.ofMinutes(5), "Banfield"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void claveMfaQueNoEsBase64FallaSinEco() {
		String invalida = "REEMPLAZAR_con_base64_de_32_bytes_generado_con_openssl_rand_base64_32";
		assertThatThrownBy(() -> new SeguridadPropiedades.Mfa(invalida, Duration.ofMinutes(5), "Banfield"))
				.isInstanceOf(IllegalStateException.class).hasMessageNotContaining(invalida);
	}

	@Test
	void claveMfaDeLongitudDistintaDe32BytesFalla() {
		for (int bytes : new int[] { 16, 24, 31, 33, 48 }) {
			String clave = base64De(bytes);
			assertThatThrownBy(() -> new SeguridadPropiedades.Mfa(clave, Duration.ofMinutes(5), "Banfield"))
					.as("%d bytes", bytes)
					.isInstanceOf(IllegalStateException.class).hasMessageNotContaining(clave);
		}
		assertThat(new SeguridadPropiedades.Mfa(base64De(32), Duration.ofMinutes(5), "Banfield").claveCifradoBytes())
				.hasSize(32);
	}

	@Test
	void duracionPendienteYEmisorMfaSeValidan() {
		String clave = base64De(32);
		assertThatThrownBy(() -> new SeguridadPropiedades.Mfa(clave, Duration.ofSeconds(30), "Banfield"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new SeguridadPropiedades.Mfa(clave, Duration.ofMinutes(16), "Banfield"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new SeguridadPropiedades.Mfa(clave, Duration.ofMinutes(5), " "))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new SeguridadPropiedades.Mfa(clave, Duration.ofMinutes(5), "A:B"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void toStringDeMfaNoExponeLaClave() {
		String clave = base64De(32);
		assertThat(new SeguridadPropiedades.Mfa(clave, Duration.ofMinutes(5), "Banfield").toString())
				.doesNotContain(clave);
	}
}
