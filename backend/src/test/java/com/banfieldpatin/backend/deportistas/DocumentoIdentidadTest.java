package com.banfieldpatin.backend.deportistas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/** DNI y CUIL: normalizacion, formato y digito verificador (REQ-DEP-01 y REQ-DEP-02), sin base de datos. */
class DocumentoIdentidadTest {

	// ---------- DNI ----------

	@ParameterizedTest
	@ValueSource(strings = { "1234567", "12345678", "123456789", "12.345.678", " 12 345 678 ", "1.234.567", "30111222" })
	void unDniDeSieteANueveDigitosEsValidoConPuntosYEspacios(String crudo) {
		assertThat(DocumentoIdentidad.dniValido(crudo)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "123456", "1234567890", "12.34.56", "1234 5678 90", "12ab5678", "abcdefgh", "12-345-678",
			"1234567a", "12,345,678", "+12345678", "１２３４５６７８" })
	void unDniConSeisODiezDigitosLetrasOGuionesEsInvalido(String crudo) {
		assertThat(DocumentoIdentidad.dniValido(crudo)).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "...", " . " })
	void unDniNuloVacioOSoloSeparadoresNoEsValido(String crudo) {
		assertThat(DocumentoIdentidad.dniValido(crudo)).isFalse();
	}

	@Test
	void normalizarDniQuitaPuntosYEspaciosYRespetaElNulo() {
		assertThat(DocumentoIdentidad.normalizarDni("12.345.678")).isEqualTo("12345678");
		assertThat(DocumentoIdentidad.normalizarDni("  30 111 222 ")).isEqualTo("30111222");
		assertThat(DocumentoIdentidad.normalizarDni(null)).isNull();
	}

	// ---------- CUIL ----------

	@Test
	void elCuilDelEjemploEsValidoYSeNormalizaSinGuiones() {
		assertThat(DocumentoIdentidad.cuilValido("20-12345678-6")).isTrue();
		assertThat(DocumentoIdentidad.normalizarCuil("20-12345678-6")).isEqualTo("20123456786");
		assertThat(DocumentoIdentidad.normalizarCuil(" 20.12345678.6 ")).isEqualTo("20123456786");
		assertThat(DocumentoIdentidad.normalizarCuil("20 12345678 6")).isEqualTo("20123456786");
		assertThat(DocumentoIdentidad.normalizarCuil(null)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "20123456786", "20-12345678-6", "20.12345678.6", "20 12345678 6" })
	void elCuilAceptaGuionesPuntosYEspacios(String crudo) {
		assertThat(DocumentoIdentidad.cuilValido(crudo)).isTrue();
	}

	@Test
	void unDigitoVerificadorIncorrectoEsInvalido() {
		for (int d = 0; d <= 9; d++) {
			boolean esperado = d == 6;
			assertThat(DocumentoIdentidad.cuilValido("2012345678" + d)).as("digito %d", d).isEqualTo(esperado);
		}
	}

	@Test
	void cuandoElRestoDa11ElDigitoVerificadorEsCero() {
		// 2030111222: suma 55, 55 % 11 = 0, r = 11 -> el digito verificador es 0.
		assertThat(DocumentoIdentidad.cuilValido("20301112220")).isTrue();
		assertThat(DocumentoIdentidad.cuilValido("20301112221")).isFalse();
		assertThat(DocumentoIdentidad.cuilValido("20301112226")).isFalse();
		// 2040000000: suma 22, r = 11 -> 0.
		assertThat(DocumentoIdentidad.cuilValido("20400000000")).isTrue();
	}

	@Test
	void cuandoElRestoDa10NingunDigitoVerificadorEsValido() {
		// 2000000001: suma 12, 12 % 11 = 1, r = 10 -> prefijo invalido, sea cual sea el onceavo digito.
		for (int d = 0; d <= 9; d++) {
			assertThat(DocumentoIdentidad.cuilValido("2000000001" + d)).as("digito %d", d).isFalse();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "2012345678", "201234567866", "2012345678-66", "2O123456786", "ab123456786", "20-1234567-86x",
			"20/12345678/6" })
	void unCuilSinOnceDigitosOConLetrasEsInvalido(String crudo) {
		assertThat(DocumentoIdentidad.cuilValido(crudo)).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "--", "..." })
	void unCuilNuloOVacioNoEsValido(String crudo) {
		assertThat(DocumentoIdentidad.cuilValido(crudo)).isFalse();
	}

	@Test
	void elPrefijoDelCuilNoSeCruzaConElDni() {
		// Sin lista blanca de prefijos ni cruce DNI-CUIL: un prefijo cualquiera con digito correcto es valido.
		// 2730111222: suma 83, r = 5.
		assertThat(DocumentoIdentidad.cuilValido("27301112225")).isTrue();
		// 2700000000: suma 38, r = 6; el DNI "00000000" ni siquiera es un DNI valido, y el CUIL no se compara.
		assertThat(DocumentoIdentidad.cuilValido("27000000006")).isTrue();
	}

	// ---------- anotaciones Bean Validation (aceptan nulo: @NotBlank decide si es obligatorio) ----------

	record Documentos(@Dni String dni, @Cuil String cuil) {
	}

	private static Set<String> campos(Documentos d) {
		try (ValidatorFactory fabrica = Validation.buildDefaultValidatorFactory()) {
			Validator validador = fabrica.getValidator();
			Set<ConstraintViolation<Documentos>> v = validador.validate(d);
			return v.stream().map(x -> x.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
		}
	}

	@Test
	void lasAnotacionesAceptanNuloYValoresValidos() {
		assertThat(campos(new Documentos(null, null))).isEmpty();
		assertThat(campos(new Documentos("12.345.678", "20-12345678-6"))).isEmpty();
	}

	@Test
	void lasAnotacionesRechazanValoresInvalidosEnElCampoCorrecto() {
		assertThat(campos(new Documentos("12ab", null))).containsExactly("dni");
		assertThat(campos(new Documentos(null, "20-12345678-7"))).containsExactly("cuil");
		assertThat(campos(new Documentos("123456", "2012345678"))).containsExactlyInAnyOrder("dni", "cuil");
	}
}
