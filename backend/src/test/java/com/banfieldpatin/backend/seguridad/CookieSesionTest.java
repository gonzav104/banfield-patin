package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

class CookieSesionTest {

	private static CookieSesion con(boolean secure, String sameSite) {
		return new CookieSesion(new SeguridadPropiedades(
				new SeguridadPropiedades.Jwt(JwtConfigTest.SECRETO, "e", Duration.ofHours(8)),
				new SeguridadPropiedades.Cookie("BP_SESION", secure, sameSite),
				new SeguridadPropiedades.Cors(null),
				new SeguridadPropiedades.Login(5, Duration.ofMinutes(15), Duration.ofMinutes(15), 10000),
					JwtConfigTest.MFA));
	}

	@Test
	void atributosPorDefecto() {
		ResponseCookie c = con(false, "Lax").crear("tok");
		assertThat(c.isHttpOnly()).isTrue();
		assertThat(c.isSecure()).isFalse();
		assertThat(c.getSameSite()).isEqualTo("Lax");
		assertThat(c.getPath()).isEqualTo("/");
		assertThat(c.getMaxAge()).isEqualTo(Duration.ofHours(8));
		assertThat(c.getValue()).isEqualTo("tok");
	}

	@Test
	void secureYSameSiteNone() {
		ResponseCookie c = con(true, "None").crear("tok");
		assertThat(c.toString()).contains("Secure", "SameSite=None", "HttpOnly");
	}

	@Test
	void laCookieConMfaPendienteDuraLoQueElTokenPendienteConLosMismosAtributos() {
		ResponseCookie c = con(true, "Lax").crearMfaPendiente("tok");
		assertThat(c.getMaxAge()).isEqualTo(Duration.ofMinutes(5));
		assertThat(c.getName()).isEqualTo("BP_SESION");
		assertThat(c.toString()).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/");
	}

	@Test
	void borrarUsaMismosAtributosConValorVacioYMaxAgeCero() {
		ResponseCookie c = con(true, "Strict").borrar();
		assertThat(c.getValue()).isEmpty();
		assertThat(c.getMaxAge()).isEqualTo(Duration.ZERO);
		assertThat(c.toString()).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/");
	}
}
