package com.banfieldpatin.backend.temporadas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class TemporadasPropiedadesTest {

	@Test
	void unPlazoDentroDelRangoSeAcepta() {
		assertThat(new TemporadasPropiedades(Duration.ofSeconds(3)).lockTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(new TemporadasPropiedades(Duration.ofMillis(100)).lockTimeout()).isEqualTo(Duration.ofMillis(100));
		assertThat(new TemporadasPropiedades(Duration.ofSeconds(30)).lockTimeout()).isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void unPlazoFueraDeRangoImpideArrancarEnLugarDeDesactivarElLimite() {
		// 0 en PostgreSQL significa "esperar para siempre": exactamente lo que se evita.
		for (Duration invalido : List.of(Duration.ZERO, Duration.ofMillis(99), Duration.ofMillis(30001), Duration.ofMinutes(5),
				Duration.ofSeconds(-1))) {
			assertThatThrownBy(() -> new TemporadasPropiedades(invalido)).isInstanceOf(IllegalStateException.class)
					.hasMessage("banfield.temporadas.lock-timeout debe estar entre 100 ms y 30 s");
		}
		assertThatThrownBy(() -> new TemporadasPropiedades(null)).isInstanceOf(IllegalStateException.class);
	}
}
