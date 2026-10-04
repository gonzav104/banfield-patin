package com.banfieldpatin.backend.seguridad;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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

import com.banfieldpatin.backend.usuarios.Rol;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
@EnableConfigurationProperties(SeguridadPropiedades.class)
public class JwtConfig {

	/** Autoridad del ADMIN que mostro su contrasena y aun no su segundo factor. */
	public static final String AUTORIDAD_MFA_PENDIENTE = "MFA_PENDIENTE";

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
		JwtAuthenticationConverter convertidor = new JwtAuthenticationConverter();
		convertidor.setJwtGrantedAuthoritiesConverter(JwtConfig::autoridades);
		convertidor.setPrincipalClaimName("sub");
		return convertidor;
	}

	/**
	 * FAMILIA obtiene ROLE_FAMILIA. Un ADMIN obtiene ROLE_ADMIN solo con mfa=COMPLETADA; con mfa=PENDIENTE obtiene
	 * unicamente la autoridad MFA_PENDIENTE (que habilita las rutas de enrolamiento/verificacion y nada mas).
	 */
	private static Collection<GrantedAuthority> autoridades(Jwt jwt) {
		String rol = jwt.getClaimAsString(ServicioTokens.CLAIM_ROL);
		if (!Rol.ADMIN.name().equals(rol)) {
			return List.of(new SimpleGrantedAuthority("ROLE_" + rol));
		}
		return switch (String.valueOf(jwt.getClaimAsString(ServicioTokens.CLAIM_MFA))) {
			case ServicioTokens.MFA_COMPLETADA -> List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
			case ServicioTokens.MFA_PENDIENTE -> List.of(new SimpleGrantedAuthority(AUTORIDAD_MFA_PENDIENTE));
			default -> List.of();
		};
	}

	/** Un token de ADMIN sin etapa MFA valida (por ejemplo emitido antes de existir el MFA) se rechaza con 401. */
	private static OAuth2TokenValidator<Jwt> reclamosObligatorios() {
		return jwt -> {
			for (String claim : CLAIMS_OBLIGATORIOS) {
				if (jwt.getClaimAsString(claim) == null) {
					return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
				}
			}
			if (Rol.ADMIN.name().equals(jwt.getClaimAsString(ServicioTokens.CLAIM_ROL))) {
				String etapa = jwt.getClaimAsString(ServicioTokens.CLAIM_MFA);
				if (!ServicioTokens.MFA_COMPLETADA.equals(etapa) && !ServicioTokens.MFA_PENDIENTE.equals(etapa)) {
					return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
				}
			}
			return OAuth2TokenValidatorResult.success();
		};
	}
}
