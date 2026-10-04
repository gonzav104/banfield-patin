package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LimitadorIntentosLoginTest {

	/** Reloj mutable para avanzar el tiempo sin dormir. */
	static class RelojMutable extends Clock {
		Instant ahora = Instant.parse("2026-01-01T10:00:00Z");

		void avanzar(Duration d) {
			ahora = ahora.plus(d);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return ahora;
		}
	}

	private final RelojMutable reloj = new RelojMutable();
	private final LimitadorIntentosLogin limitador = new LimitadorIntentosLogin(3, Duration.ofMinutes(15),
			Duration.ofMinutes(15), 100, reloj);

	private void fallar(String clave, int veces) {
		for (int i = 0; i < veces; i++) {
			limitador.registrarFallo(clave);
		}
	}

	@Test
	void bloqueaTrasNFallosYRechazaAunqueLaSiguienteSeaCorrecta() {
		String k = LimitadorIntentosLogin.clave("1.1.1.1", "a@x.com");
		fallar(k, 2);
		assertThat(limitador.estaBloqueado(k)).isFalse();
		fallar(k, 1);
		assertThat(limitador.estaBloqueado(k)).isTrue();
	}

	@Test
	void sedesbloqueaTrasElTiempoDeBloqueo() {
		String k = LimitadorIntentosLogin.clave("1.1.1.1", "a@x.com");
		fallar(k, 3);
		reloj.avanzar(Duration.ofMinutes(14));
		assertThat(limitador.estaBloqueado(k)).isTrue();
		reloj.avanzar(Duration.ofMinutes(2));
		assertThat(limitador.estaBloqueado(k)).isFalse();
	}

	@Test
	void aislaPorIp() {
		fallar(LimitadorIntentosLogin.clave("1.1.1.1", "a@x.com"), 3);
		assertThat(limitador.estaBloqueado(LimitadorIntentosLogin.clave("1.1.1.1", "a@x.com"))).isTrue();
		assertThat(limitador.estaBloqueado(LimitadorIntentosLogin.clave("2.2.2.2", "a@x.com"))).isFalse();
	}

	@Test
	void elExitoReseteaElContador() {
		String k = LimitadorIntentosLogin.clave("1.1.1.1", "a@x.com");
		fallar(k, 2);
		limitador.registrarExito(k);
		fallar(k, 2);
		assertThat(limitador.estaBloqueado(k)).isFalse();
	}

	@Test
	void losFallosFueraDeLaVentanaNoSeAcumulan() {
		String k = LimitadorIntentosLogin.clave("1.1.1.1", "a@x.com");
		fallar(k, 2);
		reloj.avanzar(Duration.ofMinutes(16));
		fallar(k, 2);
		assertThat(limitador.estaBloqueado(k)).isFalse();
	}

	@Test
	void emailsDesconocidosSeTratanIgualYLaClaveNormalizaElEmail() {
		assertThat(LimitadorIntentosLogin.clave("1.1.1.1", "  Nadie@X.com "))
				.isEqualTo(LimitadorIntentosLogin.clave("1.1.1.1", "nadie@x.com"));
		String k = LimitadorIntentosLogin.clave("1.1.1.1", "nadie@x.com");
		fallar(k, 3);
		assertThat(limitador.estaBloqueado(k)).isTrue();
	}

	@Test
	void elAlmacenEstaAcotadoYPurgaVencidosAntesDeExpulsar() {
		LimitadorIntentosLogin chico = new LimitadorIntentosLogin(3, Duration.ofMinutes(15),
				Duration.ofMinutes(15), 5, reloj);
		for (int i = 0; i < 5; i++) {
			chico.registrarFallo("k" + i);
		}
		reloj.avanzar(Duration.ofMinutes(16));
		chico.registrarFallo("nueva");
		assertThat(chico.tamanio()).isEqualTo(1);

		for (int i = 0; i < 50; i++) {
			chico.registrarFallo("m" + i);
		}
		assertThat(chico.tamanio()).isLessThanOrEqualTo(5);
	}

	@Test
	void alExpulsarPrefiereNoBloqueadas() {
		LimitadorIntentosLogin otro = new LimitadorIntentosLogin(2, Duration.ofMinutes(15), Duration.ofMinutes(15), 2,
				reloj);
		otro.registrarFallo("a");
		otro.registrarFallo("a"); // bloqueada
		reloj.avanzar(Duration.ofSeconds(1));
		otro.registrarFallo("b");
		reloj.avanzar(Duration.ofSeconds(1));
		otro.registrarFallo("c"); // lleno: debe expulsar "b", no la bloqueada "a"
		assertThat(otro.estaBloqueado("a")).isTrue();
		assertThat(otro.tamanio()).isEqualTo(2);
	}
}
