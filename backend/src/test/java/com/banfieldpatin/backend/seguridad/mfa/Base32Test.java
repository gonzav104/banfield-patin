package com.banfieldpatin.backend.seguridad.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

/** Vectores de prueba de RFC 4648 seccion 10 (Base32). */
class Base32Test {

	private static final String[][] VECTORES = {
			{ "", "" },
			{ "f", "MY======" },
			{ "fo", "MZXQ====" },
			{ "foo", "MZXW6===" },
			{ "foob", "MZXW6YQ=" },
			{ "fooba", "MZXW6YTB" },
			{ "foobar", "MZXW6YTBOI======" },
	};

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	@Test
	void codificaLosVectoresDeRfc4648ConRelleno() {
		for (String[] v : VECTORES) {
			assertThat(Base32.codificar(bytes(v[0]), true)).as("'%s'", v[0]).isEqualTo(v[1]);
		}
	}

	@Test
	void sinRellenoEsElMismoTextoSinIguales() {
		for (String[] v : VECTORES) {
			assertThat(Base32.codificar(bytes(v[0]), false)).as("'%s'", v[0]).isEqualTo(v[1].replace("=", ""));
		}
	}

	@Test
	void decodificaLosVectoresConYSinRelleno() {
		for (String[] v : VECTORES) {
			assertThat(Base32.decodificar(v[1])).as("'%s' con relleno", v[0]).isEqualTo(bytes(v[0]));
			assertThat(Base32.decodificar(v[1].replace("=", ""))).as("'%s' sin relleno", v[0]).isEqualTo(bytes(v[0]));
		}
	}

	@Test
	void decodificarAceptaMinusculas() {
		assertThat(Base32.decodificar("mzxw6ytboi")).isEqualTo(bytes("foobar"));
	}

	@Test
	void idaYVueltaConSecretosAleatoriosDe20Bytes() {
		SecureRandom azar = new SecureRandom();
		for (int i = 0; i < 200; i++) {
			byte[] secreto = new byte[Totp.LONGITUD_SECRETO_BYTES];
			azar.nextBytes(secreto);
			String codificado = Base32.codificar(secreto, false);
			assertThat(codificado).hasSize(32).matches("[A-Z2-7]{32}");
			assertThat(Base32.decodificar(codificado)).isEqualTo(secreto);
		}
	}

	@Test
	void unCaracterFueraDelAlfabetoEsUnError() {
		for (String malo : new String[] { "MZXW1", "MZXW0", "MZXW8", "MZ-W", "MZ W6" }) {
			assertThatThrownBy(() -> Base32.decodificar(malo)).as(malo).isInstanceOf(IllegalArgumentException.class);
		}
	}
}
