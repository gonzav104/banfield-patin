package com.banfieldpatin.backend.seguridad;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
@EnableConfigurationProperties(SeguridadPropiedades.class)
public class JwtConfig {

	private static final List<String> CLAIMS_OBLIGATORIOS = List.of("sub", ServicioTokens.CLAIM_ROL,
			ServicioTokens.CLAIM_ESCUELA_ID);

	@Bean
	SecretKey claveJwt(SeguridadPropiedades propiedades) {
		return new SecretKeySpec(propiedades.jwt().secretoBytes(), "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey claveJwt) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(claveJwt));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey claveJwt, SeguridadPropiedades propiedades, Clock reloj) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(claveJwt).macAlgorithm(MacAlgorithm.HS256).build();
		JwtTimestampValidator tiempo = new JwtTimestampValidator(Duration.ofSeconds(60));
		tiempo.setClock(reloj);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(tiempo,
				new JwtIssuerValidator(propiedades.jwt().emisor()), reclamosObligatorios()));
		return decoder;
	}

	@Bean
	JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter autoridades = new JwtGrantedAuthoritiesConverter();
		autoridades.setAuthoritiesClaimName(ServicioTokens.CLAIM_ROL);
		autoridades.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter convertidor = new JwtAuthenticationConverter();
		convertidor.setJwtGrantedAuthoritiesConverter(autoridades);
		convertidor.setPrincipalClaimName("sub");
		return convertidor;
	}

	private static OAuth2TokenValidator<Jwt> reclamosObligatorios() {
		return jwt -> {
			for (String claim : CLAIMS_OBLIGATORIOS) {
				if (jwt.getClaimAsString(claim) == null) {
					return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
				}
			}
			return OAuth2TokenValidatorResult.success();
		};
	}
}
