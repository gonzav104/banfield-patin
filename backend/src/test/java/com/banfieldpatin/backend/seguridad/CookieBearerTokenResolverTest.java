package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.http.Cookie;

class CookieBearerTokenResolverTest {

	final CookieBearerTokenResolver resolver = new CookieBearerTokenResolver(new CookieSesion(
			new SeguridadPropiedades(
					new SeguridadPropiedades.Jwt(JwtConfigTest.SECRETO, "e", Duration.ofHours(8)),
					new SeguridadPropiedades.Cookie("BP_SESION", false, "Lax"),
					new SeguridadPropiedades.Cors(null),
					new SeguridadPropiedades.Login(5, Duration.ofMinutes(15), Duration.ofMinutes(15), 10000))));

	private MockHttpServletRequest pedido(String ruta, Cookie... cookies) {
		MockHttpServletRequest r = new MockHttpServletRequest("POST", ruta);
		r.setCookies(cookies);
		return r;
	}

	@Test
	void leeLaCookieDeSesion() {
		assertThat(resolver.resolve(pedido("/api/auth/me", new Cookie("BP_SESION", "abc")))).isEqualTo("abc");
	}

	@Test
	void ignoraElHeaderAuthorization() {
		MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/auth/me");
		r.addHeader("Authorization", "Bearer xyz");
		assertThat(resolver.resolve(r)).isNull();
	}

	@Test
	void ignoraOtrasCookies() {
		assertThat(resolver.resolve(pedido("/api/auth/me", new Cookie("otra", "abc")))).isNull();
	}

	@Test
	void rutasPublicasDevuelvenNullAunConCookie() {
		for (String ruta : new String[] { "/api/auth/login", "/api/auth/admin/login", "/api/auth/csrf",
				"/api/auth/invitaciones/validar", "/api/auth/registro/invitacion" }) {
			assertThat(resolver.resolve(pedido(ruta, new Cookie("BP_SESION", "vencida")))).as(ruta).isNull();
		}
	}

	@Test
	void logoutLeeLaCookie() {
		assertThat(resolver.resolve(pedido("/api/auth/logout", new Cookie("BP_SESION", "abc")))).isEqualTo("abc");
	}
}
