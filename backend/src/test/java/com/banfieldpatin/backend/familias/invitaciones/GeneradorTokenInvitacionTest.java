package com.banfieldpatin.backend.familias.invitaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class GeneradorTokenInvitacionTest {

	private final GeneradorTokenInvitacion generador = new GeneradorTokenInvitacion();

	@Test
	void eltokenDecodificaAlMenosA32BytesYEsUrlSafeSinRelleno() {
		String token = generador.generar();

		assertThat(Base64.getUrlDecoder().decode(token)).hasSizeGreaterThanOrEqualTo(32);
		assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+").doesNotContain("=");
	}

	@Test
	void dosTokensGeneradosSonDistintos() {
		Set<String> tokens = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			tokens.add(generador.generar());
		}
		assertThat(tokens).hasSize(200);
	}

	@Test
	void elHashEsSha256HexMinusculaDeterministaYDistintoDelToken() {
		String token = generador.generar();
		String hash = GeneradorTokenInvitacion.sha256Hex(token);

		assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(token);
		assertThat(GeneradorTokenInvitacion.sha256Hex(token)).isEqualTo(hash);
		assertThat(GeneradorTokenInvitacion.sha256Hex(token + "x")).isNotEqualTo(hash);
	}

	@Test
	void vectorConocidoDeSha256() {
		assertThat(GeneradorTokenInvitacion.sha256Hex("abc"))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}
}
