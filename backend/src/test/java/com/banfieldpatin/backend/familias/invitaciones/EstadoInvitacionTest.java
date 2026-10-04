package com.banfieldpatin.backend.familias.invitaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.banfieldpatin.backend.FixturesDominio;

class EstadoInvitacionTest {

	private static final Instant CREADA = Instant.parse("2026-01-01T00:00:00Z");
	private static final Instant EXPIRA = Instant.parse("2026-01-08T00:00:00Z");

	private Invitacion invitacion() {
		return FixturesDominio.invitacion(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				CREADA, EXPIRA);
	}

	@Test
	void sinUsarNiRevocarYVigenteEsPendiente() {
		assertThat(EstadoInvitacion.desde(invitacion(), EXPIRA.minusSeconds(1))).isEqualTo(EstadoInvitacion.PENDIENTE);
	}

	@Test
	void usadaGanaAunqueHayaVencido() {
		Invitacion i = invitacion();
		ReflectionTestUtils.setField(i, "usadoEn", CREADA.plusSeconds(60));
		assertThat(EstadoInvitacion.desde(i, EXPIRA.plusSeconds(1))).isEqualTo(EstadoInvitacion.USADA);
	}

	@Test
	void revocadaGanaAunqueHayaVencido() {
		Invitacion i = invitacion();
		ReflectionTestUtils.setField(i, "revocadaEn", CREADA.plusSeconds(60));
		assertThat(EstadoInvitacion.desde(i, EXPIRA.plusSeconds(1))).isEqualTo(EstadoInvitacion.REVOCADA);
	}

	@Test
	void sinUsarNiRevocarYVencidaEsExpirada() {
		assertThat(EstadoInvitacion.desde(invitacion(), EXPIRA.plusSeconds(1))).isEqualTo(EstadoInvitacion.EXPIRADA);
	}

	@Test
	void enElInstanteExactoDeExpiracionYaEstaExpirada() {
		assertThat(EstadoInvitacion.desde(invitacion(), EXPIRA)).isEqualTo(EstadoInvitacion.EXPIRADA);
	}
}
