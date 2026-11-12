package com.banfieldpatin.backend.familias.invitaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlBaseFrontendTest {

	@Test
	void vaciaONulaSignificaSinEnlace() {
		assertThat(UrlBaseFrontend.normalizar(null)).isEmpty();
		assertThat(UrlBaseFrontend.normalizar("  ")).isEmpty();
	}

	@Test
	void normalizaQuitandoEspaciosYBarraFinal() {
		assertThat(UrlBaseFrontend.normalizar(" https://portal.example.org/ ")).isEqualTo("https://portal.example.org");
		assertThat(UrlBaseFrontend.normalizar("https://example.org/portal/")).isEqualTo("https://example.org/portal");
		assertThat(UrlBaseFrontend.normalizar("http://127.0.0.1:5173")).isEqualTo("http://127.0.0.1:5173");
	}

	@ParameterizedTest
	@ValueSource(strings = { "portal.example.org", "/relativa", "ftp://example.org", "javascript:alert(1)",
			"https://usuario:clave@example.org", "https://example.org/?a=1", "https://example.org/#x", "https://",
			"https://exa mple.org" })
	void rechazaUrlsNoConfiables(String valor) {
		assertThatThrownBy(() -> UrlBaseFrontend.normalizar(valor)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("banfield.frontend.url-base");
	}

	@Test
	void produccionExigeUnaUrlHttpsConfigurada() {
		assertThatThrownBy(() -> UrlBaseFrontend.exigirEnProduccion("")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("FRONTEND_URL_BASE");
		assertThatThrownBy(() -> UrlBaseFrontend.exigirEnProduccion("http://portal.example.org"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("https");
		UrlBaseFrontend.exigirEnProduccion("https://portal.example.org");
	}
}
