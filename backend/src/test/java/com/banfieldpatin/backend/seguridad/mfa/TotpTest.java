package com.banfieldpatin.backend.seguridad.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

/** RFC 6238 (apendice B, HMAC-SHA1), ventana de +-1 paso y anti-repeticion. */
class TotpTest {

	/** Secreto ASCII "12345678901234567890" del apendice B de RFC 6238 para SHA-1. */
	private static final byte[] SECRETO = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

	/** {segundos Unix, TOTP de 8 digitos del RFC}. */
	private static final String[][] VECTORES_RFC = {
			{ "59", "94287082" },
			{ "1111111109", "07081804" },
			{ "1111111111", "14050471" },
			{ "1234567890", "89005924" },
			{ "2000000000", "69279037" },
			{ "20000000000", "65353130" },
	};

	@Test
	void losVectoresDeRfc6238DanLosValoresDe8Digitos() {
		for (String[] v : VECTORES_RFC) {
			long paso = Totp.pasoDe(Instant.ofEpochSecond(Long.parseLong(v[0])));
			assertThat(Totp.codigo(SECRETO, paso, 8)).as("T=%s", v[0]).isEqualTo(v[1]);
		}
	}

	@Test
	void conSeisDigitosSonLosUltimosSeisDelVectorDeRfc() {
		for (String[] v : VECTORES_RFC) {
			long paso = Totp.pasoDe(Instant.ofEpochSecond(Long.parseLong(v[0])));
			assertThat(Totp.codigo(SECRETO, paso)).as("T=%s", v[0]).isEqualTo(v[1].substring(2));
		}
		// Valores concretos pedidos por la especificacion del cambio.
		assertThat(Totp.codigo(SECRETO, Totp.pasoDe(Instant.ofEpochSecond(59)))).isEqualTo("287082");
		assertThat(Totp.codigo(SECRETO, Totp.pasoDe(Instant.ofEpochSecond(1111111109L)))).isEqualTo("081804");
	}

	@Test
	void conservaLosCerosInicialesDelCodigo() {
		assertThat(Totp.codigo(SECRETO, Totp.pasoDe(Instant.ofEpochSecond(1111111109L)))).hasSize(6).startsWith("0");
	}

	@Test
	void elPasoCambiaCadaTreintaSegundos() {
		assertThat(Totp.pasoDe(Instant.ofEpochSecond(0))).isZero();
		assertThat(Totp.pasoDe(Instant.ofEpochSecond(29))).isZero();
		assertThat(Totp.pasoDe(Instant.ofEpochSecond(30))).isEqualTo(1);
		assertThat(Totp.pasoDe(Instant.ofEpochSecond(59))).isEqualTo(1);
		assertThat(Totp.pasoDe(Instant.ofEpochSecond(60))).isEqualTo(2);
	}

	// ---------- ventana +-1 ----------

	private static final Instant AHORA = Instant.ofEpochSecond(1_700_000_010L);
	private static final long PASO = Totp.pasoDe(AHORA);

	@Test
	void aceptaElPasoActual() {
		assertThat(Totp.verificar(SECRETO, Totp.codigo(SECRETO, PASO), AHORA, null)).hasValue(PASO);
	}

	@Test
	void aceptaUnPasoAtrasYUnoAdelante() {
		assertThat(Totp.verificar(SECRETO, Totp.codigo(SECRETO, PASO - 1), AHORA, null)).hasValue(PASO - 1);
		assertThat(Totp.verificar(SECRETO, Totp.codigo(SECRETO, PASO + 1), AHORA, null)).hasValue(PASO + 1);
	}

	@Test
	void rechazaDosPasosAtrasYDosAdelante() {
		assertThat(Totp.verificar(SECRETO, Totp.codigo(SECRETO, PASO - 2), AHORA, null)).isEmpty();
		assertThat(Totp.verificar(SECRETO, Totp.codigo(SECRETO, PASO + 2), AHORA, null)).isEmpty();
	}

	@Test
	void elLimiteDeLaVentanaSigueElInstanteExactoDelCambioDePaso() {
		// Ultimo segundo del paso P: el codigo de P-1 aun vale; un segundo despues (paso P+1) ya no vale el de P-1.
		Instant finDelPaso = Instant.ofEpochSecond((PASO + 1) * Totp.PASO_SEGUNDOS - 1);
		Instant inicioDelSiguiente = finDelPaso.plusSeconds(1);
		String codigoAnterior = Totp.codigo(SECRETO, PASO - 1);

		assertThat(Totp.verificar(SECRETO, codigoAnterior, finDelPaso, null)).hasValue(PASO - 1);
		assertThat(Totp.verificar(SECRETO, codigoAnterior, inicioDelSiguiente, null)).isEmpty();
	}

	@Test
	void rechazaUnCodigoIncorrectoOConOtroSecreto() {
		String correcto = Totp.codigo(SECRETO, PASO);
		String incorrecto = correcto.equals("000000") ? "000001" : "000000";
		assertThat(Totp.verificar(SECRETO, incorrecto, AHORA, null)).isEmpty();
		assertThat(Totp.verificar("otro-secreto-de-20-by".getBytes(StandardCharsets.US_ASCII), correcto, AHORA, null))
				.isEmpty();
	}

	// ---------- anti-repeticion ----------

	@Test
	void unPasoYaUsadoSeRechazaYUnoPosteriorSeAcepta() {
		String codigoActual = Totp.codigo(SECRETO, PASO);
		assertThat(Totp.verificar(SECRETO, codigoActual, AHORA, PASO)).isEmpty();
		assertThat(Totp.verificar(SECRETO, codigoActual, AHORA, PASO - 1)).hasValue(PASO);
		assertThat(Totp.verificar(SECRETO, codigoActual, AHORA, PASO + 5)).isEmpty();
	}

	@Test
	void unPasoAnteriorAlUltimoUsadoSeRechazaAunDentroDeLaVentana() {
		OptionalLong resultado = Totp.verificar(SECRETO, Totp.codigo(SECRETO, PASO - 1), AHORA, PASO);
		assertThat(resultado).isEmpty();
	}

	@Test
	void trasAceptarUnPasoAdelantadoElActualYaNoSirve() {
		long adelantado = Totp.verificar(SECRETO, Totp.codigo(SECRETO, PASO + 1), AHORA, null).getAsLong();
		assertThat(Totp.verificar(SECRETO, Totp.codigo(SECRETO, PASO), AHORA, adelantado)).isEmpty();
	}
}
