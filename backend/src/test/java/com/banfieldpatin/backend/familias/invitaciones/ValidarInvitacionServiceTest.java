package com.banfieldpatin.backend.familias.invitaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.banfieldpatin.backend.FixturesDominio;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionPublicaRespuesta;
import com.banfieldpatin.backend.seguridad.LimitadorIntentosLogin;

class ValidarInvitacionServiceTest {

	private static final Instant AHORA = Instant.parse("2026-03-01T12:00:00Z");
	private static final DatosSolicitud IP_1 = new DatosSolicitud("10.0.0.1", "JUnit");
	private static final DatosSolicitud IP_2 = new DatosSolicitud("10.0.0.2", "JUnit");

	private final InvitacionRepository invitaciones = mock(InvitacionRepository.class);
	private final EscuelaRepository escuelas = mock(EscuelaRepository.class);
	private final FamiliaRepository familias = mock(FamiliaRepository.class);
	private final Clock reloj = Clock.fixed(AHORA, ZoneOffset.UTC);
	private final LimitadorIntentosLogin limitador = new LimitadorIntentosLogin(3, Duration.ofMinutes(15),
			Duration.ofMinutes(15), 100, reloj);
	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final String token = new GeneradorTokenInvitacion().generar();
	private Invitacion invitacion;
	private ValidarInvitacionService servicio;

	@BeforeEach
	void preparar() {
		invitacion = FixturesDominio.invitacion(UUID.randomUUID(), escuelaId, familiaId, UUID.randomUUID(),
				AHORA.minusSeconds(3600), AHORA.plusSeconds(3600));
		ReflectionTestUtils.setField(invitacion, "emailSugerido", "madre@example.com");
		when(invitaciones.findByTokenHash(GeneradorTokenInvitacion.sha256Hex(token)))
				.thenReturn(Optional.of(invitacion));
		when(escuelas.findById(escuelaId)).thenReturn(Optional.of(FixturesDominio.escuela(escuelaId, true)));
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId))
				.thenReturn(Optional.of(FixturesDominio.familia(familiaId, escuelaId, true)));
		servicio = new ValidarInvitacionService(invitaciones, escuelas, familias, limitador, reloj);
	}

	private ExcepcionNegocio fallo(String tokenPedido) {
		return (ExcepcionNegocio) catchThrowable(() -> servicio.validar(tokenPedido, IP_1));
	}

	private static Throwable catchThrowable(Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			return t;
		}
		throw new AssertionError("Se esperaba una excepcion");
	}

	@Test
	void tokenPendienteDevuelveContextoMinimo() {
		InvitacionPublicaRespuesta r = servicio.validar(token, IP_1);

		assertThat(r.valida()).isTrue();
		assertThat(r.escuelaNombre()).isEqualTo("Escuela de prueba");
		assertThat(r.emailSugerido()).isEqualTo("madre@example.com");
		assertThat(r.expiraEn()).isEqualTo(AHORA.plusSeconds(3600));
	}

	@Test
	void laRespuestaNoTieneIdsInternos() {
		List<String> campos = Arrays.stream(InvitacionPublicaRespuesta.class.getRecordComponents())
				.map(RecordComponent::getName).toList();

		assertThat(campos).containsExactlyInAnyOrder("valida", "escuelaNombre", "emailSugerido", "expiraEn");
	}

	@Test
	void todasLasCausasDeRechazoDanElMismoError() {
		ExcepcionNegocio referencia = fallo("a".repeat(43)); // inexistente

		assertThat(referencia.getEstado()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(referencia.getCodigo()).isEqualTo("INVITACION_NO_DISPONIBLE");

		// vencida
		ReflectionTestUtils.setField(invitacion, "expiraEn", AHORA);
		assertMismoError(referencia, fallo(token));
		ReflectionTestUtils.setField(invitacion, "expiraEn", AHORA.plusSeconds(3600));

		// revocada
		ReflectionTestUtils.setField(invitacion, "revocadaEn", AHORA.minusSeconds(10));
		assertMismoError(referencia, fallo(token));
		ReflectionTestUtils.setField(invitacion, "revocadaEn", null);

		// usada
		ReflectionTestUtils.setField(invitacion, "usadoEn", AHORA.minusSeconds(10));
		assertMismoError(referencia, fallo(token));
		ReflectionTestUtils.setField(invitacion, "usadoEn", null);

		// escuela inactiva
		when(escuelas.findById(escuelaId)).thenReturn(Optional.of(FixturesDominio.escuela(escuelaId, false)));
		assertMismoError(referencia, fallo(token));
		when(escuelas.findById(escuelaId)).thenReturn(Optional.of(FixturesDominio.escuela(escuelaId, true)));

		// familia inactiva
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId))
				.thenReturn(Optional.of(FixturesDominio.familia(familiaId, escuelaId, false)));
		assertMismoError(referencia, fallo(token));
	}

	@Test
	void tokenMalFormadoDaElMismoErrorSinConsultarLaBase() {
		ExcepcionNegocio referencia = fallo("a".repeat(43));
		org.mockito.Mockito.clearInvocations(invitaciones);

		for (String malo : new String[] { "corto", "x".repeat(44), "a".repeat(42) + "!", "", "   ", null }) {
			assertMismoError(referencia, fallo(malo));
		}
		verify(invitaciones, never()).findByTokenHash(anyString());
	}

	@Test
	void tokenDeOtraInvitacionInexistenteNoConsultaEscuela() {
		fallo("b".repeat(43));

		verify(escuelas, never()).findById(escuelaId);
	}

	@Test
	void tokenValidoNoLlamaALaBaseConTextoPlano() {
		servicio.validar(token, IP_1);

		verify(invitaciones, never()).findByTokenHash(token);
		verify(invitaciones).findByTokenHash(GeneradorTokenInvitacion.sha256Hex(token));
	}

	@Test
	void lasValidacionesFallidasRepetidasDesdeUnaIpSeBloqueanConElMismoError() {
		ExcepcionNegocio referencia = fallo("a".repeat(43));
		fallo("c".repeat(43));
		fallo("d".repeat(43)); // 3 fallos: bloqueada

		// Aun con un token valido, la IP bloqueada recibe el error uniforme (sin 429 ni Retry-After).
		assertMismoError(referencia, fallo(token));
	}

	@Test
	void elBloqueoDeUnaIpNoAfectaALaOtra() {
		fallo("a".repeat(43));
		fallo("c".repeat(43));
		fallo("d".repeat(43));

		assertThat(servicio.validar(token, IP_2).valida()).isTrue();
	}

	@Test
	void elExitoNoReiniciaElContadorDeFallos() {
		fallo("a".repeat(43));
		fallo("c".repeat(43));
		servicio.validar(token, IP_1);
		fallo("d".repeat(43)); // tercer fallo: el exito intermedio no borro los anteriores

		assertThatThrownBy(() -> servicio.validar(token, IP_1)).isInstanceOf(ExcepcionNegocio.class);
	}

	private static void assertMismoError(ExcepcionNegocio esperado, ExcepcionNegocio actual) {
		assertThat(actual.getEstado()).isEqualTo(esperado.getEstado());
		assertThat(actual.getCodigo()).isEqualTo(esperado.getCodigo());
		assertThat(actual.getMessage()).isEqualTo(esperado.getMessage());
	}
}
