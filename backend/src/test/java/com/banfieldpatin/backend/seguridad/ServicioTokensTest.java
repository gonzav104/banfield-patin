package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.banfieldpatin.backend.usuarios.Rol;

class ServicioTokensTest {

	final JwtConfig config = new JwtConfig();
	final SeguridadPropiedades props = JwtConfigTest.props(JwtConfigTest.SECRETO, "emisor-test");
	final javax.crypto.SecretKey clave = config.claveJwt(props);
	final ServicioTokens tokens = new ServicioTokens(config.jwtEncoder(clave), props, JwtConfigTest.RELOJ);

	private Jwt emitirYDecodificar(Rol rol, UUID familia) {
		return config.jwtDecoder(clave, props, JwtConfigTest.RELOJ)
				.decode(tokens.emitir(UUID.randomUUID(), rol, UUID.randomUUID(), familia));
	}

	@Test
	void laVersionDeSesionViajaComoEnteroYSinElClaimSeLeeComoCero() {
		var decoder = config.jwtDecoder(clave, props, JwtConfigTest.RELOJ);
		Jwt conVersion = decoder.decode(tokens.emitir(UUID.randomUUID(), Rol.FAMILIA, UUID.randomUUID(), UUID.randomUUID(), 9L));
		assertThat(ServicioTokens.versionSesion(conVersion)).isEqualTo(9L);
		assertThat(ServicioTokens.versionSesion(decoder.decode(tokens.emitirMfaPendiente(UUID.randomUUID(), UUID.randomUUID(), 2L))))
				.isEqualTo(2L);
		assertThat(ServicioTokens.versionSesion(Jwt.withTokenValue("t").header("alg", "HS256").subject("x").build())).isZero();
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> tokens.emitir(UUID.randomUUID(), Rol.FAMILIA, UUID.randomUUID(), UUID.randomUUID(), -1L))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void adminNoLlevaFamiliaYSoloTieneClaimsMinimos() {
		Jwt jwt = emitirYDecodificar(Rol.ADMIN, null);
		assertThat(jwt.getClaims().keySet())
				.containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "jti", "rol", "escuela_id", "mfa", "version_sesion");
		assertThat(jwt.getClaimAsString("mfa")).isEqualTo("COMPLETADA");
		assertThat(jwt.getExpiresAt()).isEqualTo(JwtConfigTest.AHORA.plusSeconds(8 * 3600));
	}

	@Test
	void laSesionCompletaDeFamiliaNoLlevaClaimMfa() {
		Jwt jwt = emitirYDecodificar(Rol.FAMILIA, UUID.randomUUID());
		assertThat(jwt.getClaims()).doesNotContainKey("mfa");
		assertThat(ServicioTokens.mfaPendiente(jwt)).isFalse();
	}

	@Test
	void elTokenConMfaPendienteEsDeVidaCortaYMarcaLaEtapa() {
		UUID usuario = UUID.randomUUID();
		UUID escuela = UUID.randomUUID();
		Jwt jwt = config.jwtDecoder(clave, props, JwtConfigTest.RELOJ).decode(tokens.emitirMfaPendiente(usuario, escuela));

		assertThat(jwt.getClaims().keySet())
				.containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "jti", "rol", "escuela_id", "mfa", "version_sesion");
		assertThat(jwt.getClaimAsString("mfa")).isEqualTo("PENDIENTE");
		assertThat(jwt.getClaimAsString("rol")).isEqualTo("ADMIN");
		assertThat(jwt.getSubject()).isEqualTo(usuario.toString());
		assertThat(jwt.getExpiresAt()).isEqualTo(JwtConfigTest.AHORA.plus(Duration.ofMinutes(5)));
		assertThat(ServicioTokens.mfaPendiente(jwt)).isTrue();
		assertThat(jwt.getClaims()).doesNotContainKeys("familia_id", "email", "password", "password_hash");
	}

	@Test
	void elTokenPendienteRenovadoCuentaSuVencimientoDesdeAhoraYLlevaLaMarca() {
		UUID usuario = UUID.randomUUID();
		UUID escuela = UUID.randomUUID();
		var decoder = config.jwtDecoder(clave, props, JwtConfigTest.RELOJ);
		Jwt original = decoder.decode(tokens.emitirMfaPendiente(usuario, escuela));
		Jwt renovado = decoder.decode(tokens.emitirMfaPendienteRenovado(usuario, escuela));

		assertThat(renovado.getClaims().keySet())
				.containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "jti", "rol", "escuela_id", "mfa", "mfa_renovado", "version_sesion");
		assertThat(renovado.getClaimAsString("mfa")).isEqualTo("PENDIENTE");
		assertThat(renovado.getExpiresAt()).isEqualTo(JwtConfigTest.AHORA.plus(Duration.ofMinutes(5)));
		assertThat(ServicioTokens.mfaPendiente(renovado)).isTrue();
		assertThat(ServicioTokens.mfaRenovado(renovado)).isTrue();
		assertThat(ServicioTokens.mfaRenovado(original)).isFalse();
		assertThat(renovado.getId()).isNotEqualTo(original.getId());
	}

	@Test
	void ningunTokenDeSesionCompletaLlevaLaMarcaDeRenovacion() {
		assertThat(ServicioTokens.mfaRenovado(emitirYDecodificar(Rol.ADMIN, null))).isFalse();
		assertThat(ServicioTokens.mfaRenovado(emitirYDecodificar(Rol.FAMILIA, UUID.randomUUID()))).isFalse();
	}

	@Test
	void mfaPendienteEsFalsoParaUnAdminConSesionCompleta() {
		assertThat(ServicioTokens.mfaPendiente(emitirYDecodificar(Rol.ADMIN, null))).isFalse();
	}

	@Test
	void familiaIncluyeFamiliaIdSinEmailNiPassword() {
		UUID familia = UUID.randomUUID();
		Jwt jwt = emitirYDecodificar(Rol.FAMILIA, familia);
		assertThat(jwt.getClaims().keySet())
				.containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "jti", "rol", "escuela_id", "familia_id", "version_sesion");
		assertThat(UsuarioAutenticado.desde(jwt).familiaId()).isEqualTo(familia);
		assertThat(jwt.getClaims()).doesNotContainKeys("email", "password", "password_hash");
	}

	@Test
	void cadaTokenTieneJtiDistinto() {
		assertThat(emitirYDecodificar(Rol.ADMIN, null).getId()).isNotEqualTo(emitirYDecodificar(Rol.ADMIN, null).getId());
	}
}
