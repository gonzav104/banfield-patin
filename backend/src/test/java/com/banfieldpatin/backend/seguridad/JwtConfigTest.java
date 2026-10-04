package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.banfieldpatin.backend.usuarios.Rol;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

class JwtConfigTest {

	static final Instant AHORA = Instant.parse("2026-01-10T12:00:00Z");
	static final Clock RELOJ = Clock.fixed(AHORA, ZoneOffset.UTC);
	static final String SECRETO = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
	static final String OTRO_SECRETO = Base64.getEncoder().encodeToString("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ".getBytes(StandardCharsets.UTF_8));

	static SeguridadPropiedades props(String secreto, String emisor) {
		return new SeguridadPropiedades(
				new SeguridadPropiedades.Jwt(secreto, emisor, Duration.ofHours(8)),
				new SeguridadPropiedades.Cookie("BP_SESION", false, "Lax"),
				new SeguridadPropiedades.Cors(null),
				new SeguridadPropiedades.Login(5, Duration.ofMinutes(15), Duration.ofMinutes(15), 10000));
	}

	final JwtConfig config = new JwtConfig();
	final SeguridadPropiedades props = props(SECRETO, "emisor-test");
	final SecretKey clave = config.claveJwt(props);
	final JwtDecoder decoder = config.jwtDecoder(clave, props, RELOJ);
	final ServicioTokens tokens = new ServicioTokens(config.jwtEncoder(clave), props, RELOJ);

	@Test
	void roundtripConservaClaims() {
		UUID id = UUID.randomUUID();
		UUID escuela = UUID.randomUUID();
		Jwt jwt = decoder.decode(tokens.emitir(id, Rol.ADMIN, escuela, null));
		assertThat(jwt.getSubject()).isEqualTo(id.toString());
		assertThat(jwt.getClaimAsString("rol")).isEqualTo("ADMIN");
		assertThat(UsuarioAutenticado.desde(jwt).escuelaId()).isEqualTo(escuela);
	}

	@Test
	void claveDistintaEsRechazada() {
		SecretKey otra = new SecretKeySpec(Base64.getDecoder().decode(OTRO_SECRETO), "HmacSHA256");
		String token = new ServicioTokens(config.jwtEncoder(otra), props, RELOJ)
				.emitir(UUID.randomUUID(), Rol.ADMIN, UUID.randomUUID(), null);
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void algNoneEsRechazado() {
		String cabecera = b64("{\"alg\":\"none\"}");
		String cuerpo = b64("{\"sub\":\"" + UUID.randomUUID() + "\",\"iss\":\"emisor-test\",\"rol\":\"ADMIN\","
				+ "\"escuela_id\":\"" + UUID.randomUUID() + "\",\"exp\":" + AHORA.plusSeconds(3600).getEpochSecond() + "}");
		assertThatThrownBy(() -> decoder.decode(cabecera + "." + cuerpo + ".")).isInstanceOf(BadJwtException.class);
	}

	@Test
	void expiradoEsRechazado() {
		Clock pasado = Clock.fixed(AHORA.minus(Duration.ofHours(10)), ZoneOffset.UTC);
		String token = new ServicioTokens(config.jwtEncoder(clave), props, pasado)
				.emitir(UUID.randomUUID(), Rol.ADMIN, UUID.randomUUID(), null);
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void emisorDistintoEsRechazado() {
		String token = new ServicioTokens(config.jwtEncoder(clave), props("" + SECRETO, "otro-emisor"), RELOJ)
				.emitir(UUID.randomUUID(), Rol.ADMIN, UUID.randomUUID(), null);
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void claimObligatorioAusenteEsRechazado() {
		JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(clave));
		JwtClaimsSet sinRol = JwtClaimsSet.builder().issuer("emisor-test").subject(UUID.randomUUID().toString())
				.issuedAt(AHORA).expiresAt(AHORA.plusSeconds(3600)).claim("escuela_id", UUID.randomUUID().toString())
				.build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), sinRol))
				.getTokenValue();
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void autoridadesTienenPrefijoRole() {
		Jwt jwt = decoder.decode(tokens.emitir(UUID.randomUUID(), Rol.FAMILIA, UUID.randomUUID(), UUID.randomUUID()));
		var auth = config.jwtAuthenticationConverter().convert(jwt);
		assertThat(auth.getAuthorities()).extracting(a -> a.getAuthority()).contains("ROLE_FAMILIA").doesNotContain("ROLE_ADMIN");
	}

	@Test
	void secretoCortoFallaAlConfigurar() {
		assertThatThrownBy(() -> props(Base64.getEncoder().encodeToString(new byte[16]), "e"))
				.isInstanceOf(IllegalStateException.class);
	}

	private static String b64(String s) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}
}
