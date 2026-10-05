package com.banfieldpatin.backend.familias.vinculos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.banfieldpatin.backend.FixturesDominio;

/** Maquina de estados del vinculo: sin fila / ACTIVO / REVOCADO / PENDIENTE|RECHAZADO frente a vincular, revocar y principal. */
class FamiliaDeportistaTest {

	private static final Instant T1 = Instant.parse("2026-03-01T10:00:00Z");
	private static final Instant T2 = Instant.parse("2026-04-01T10:00:00Z");

	private final UUID escuela = UUID.randomUUID();
	private final UUID familia = UUID.randomUUID();
	private final UUID deportista = UUID.randomUUID();
	private final UUID admin = UUID.randomUUID();

	private FamiliaDeportista con(EstadoVinculo estado, boolean principal) {
		return FixturesDominio.vinculo(UUID.randomUUID(), escuela, familia, deportista, estado, principal);
	}

	// ---------- sin fila: vincular ----------

	@Test
	void vincularSinFilaCreaUnVinculoActivoConSuAutorizacionYElPrincipalEnExplicito() {
		FamiliaDeportista principal = FamiliaDeportista.activo(escuela, familia, deportista, admin, T1, true);
		FamiliaDeportista secundario = FamiliaDeportista.activo(escuela, familia, deportista, admin, T1, false);

		assertThat(principal.getEstado()).isEqualTo(EstadoVinculo.ACTIVO);
		assertThat(principal.getEscuelaId()).isEqualTo(escuela);
		assertThat(principal.getFamiliaId()).isEqualTo(familia);
		assertThat(principal.getDeportistaId()).isEqualTo(deportista);
		assertThat(principal.getAutorizadoPor()).isEqualTo(admin);
		assertThat(principal.getAutorizadoEn()).isEqualTo(T1);
		assertThat(principal.isEsPrincipal()).isTrue();
		// El valor false se respeta: nunca hay un valor por defecto true.
		assertThat(secundario.isEsPrincipal()).isFalse();
	}

	// ---------- ACTIVO ----------

	@Test
	void vincularUnVinculoActivoNoSeReactivaYRevocarloLoDejaRevocadoSinPrincipal() {
		FamiliaDeportista fd = con(EstadoVinculo.ACTIVO, true);

		assertThatThrownBy(() -> fd.reactivar(admin, T2, false)).isInstanceOf(IllegalStateException.class);
		assertThat(fd.revocar()).isTrue();
		assertThat(fd.getEstado()).isEqualTo(EstadoVinculo.REVOCADO);
		assertThat(fd.isEsPrincipal()).isFalse();
		// Conserva quien lo autorizo (la historia vive en el vinculo y en la auditoria).
		assertThat(fd.getAutorizadoPor()).isNotNull();
		assertThat(fd.getAutorizadoEn()).isNotNull();
	}

	@Test
	void marcarPrincipalEsIdempotenteYQuitarPrincipalDevuelveSiHuboCambio() {
		FamiliaDeportista fd = con(EstadoVinculo.ACTIVO, false);

		assertThat(fd.marcarPrincipal()).isTrue();
		assertThat(fd.isEsPrincipal()).isTrue();
		assertThat(fd.marcarPrincipal()).isFalse();
		assertThat(fd.quitarPrincipal()).isTrue();
		assertThat(fd.isEsPrincipal()).isFalse();
		assertThat(fd.quitarPrincipal()).isFalse();
	}

	// ---------- REVOCADO ----------

	@Test
	void unVinculoRevocadoSeReutilizaConAutorizacionNuevaYPrincipalSegunSeDecida() {
		FamiliaDeportista fd = con(EstadoVinculo.ACTIVO, true);
		UUID id = fd.getId();
		fd.revocar();

		fd.reactivar(admin, T2, false);

		assertThat(fd.getId()).isEqualTo(id);
		assertThat(fd.getEstado()).isEqualTo(EstadoVinculo.ACTIVO);
		assertThat(fd.isEsPrincipal()).isFalse();
		assertThat(fd.getAutorizadoPor()).isEqualTo(admin);
		assertThat(fd.getAutorizadoEn()).isEqualTo(T2);
	}

	@Test
	void revocarUnVinculoYaRevocadoNoCambiaNada() {
		FamiliaDeportista fd = con(EstadoVinculo.REVOCADO, false);

		assertThat(fd.revocar()).isFalse();
		assertThat(fd.getEstado()).isEqualTo(EstadoVinculo.REVOCADO);
	}

	@Test
	void unVinculoRevocadoNoPuedeSerPrincipal() {
		FamiliaDeportista fd = con(EstadoVinculo.REVOCADO, false);

		assertThatThrownBy(fd::marcarPrincipal).isInstanceOf(IllegalStateException.class);
		assertThat(fd.isEsPrincipal()).isFalse();
	}

	// ---------- PENDIENTE / RECHAZADO ----------

	@Test
	void pendienteYRechazadoSeReutilizanComoActivoPeroNoSeRevocanNiSePromueven() {
		for (EstadoVinculo estado : new EstadoVinculo[] { EstadoVinculo.PENDIENTE, EstadoVinculo.RECHAZADO }) {
			// V1 deja es_principal = true por defecto en filas PENDIENTE: nunca debe sobrevivir a una reactivacion.
			FamiliaDeportista fd = con(estado, true);
			assertThat(fd.revocar()).as(estado.name()).isFalse();
			assertThat(fd.getEstado()).isEqualTo(estado);
			assertThatThrownBy(fd::marcarPrincipal).isInstanceOf(IllegalStateException.class);

			fd.reactivar(admin, T2, false);

			assertThat(fd.getEstado()).as(estado.name()).isEqualTo(EstadoVinculo.ACTIVO);
			assertThat(fd.isEsPrincipal()).isFalse();
			assertThat(fd.getAutorizadoPor()).isEqualTo(admin);
			assertThat(ReflectionTestUtils.getField(fd, "autorizadoEn")).isEqualTo(T2);
		}
	}
}
