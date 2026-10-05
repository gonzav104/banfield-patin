package com.banfieldpatin.backend.seguridad;

import java.time.Clock;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * Cadena unica y stateless. JWT en cookie HttpOnly + CSRF (cookie XSRF-TOKEN legible, header X-XSRF-TOKEN).
 * AC1 verificado: CsrfConfigurer.spa() existe en Spring Security 7.1.1.
 */
@Configuration
@EnableMethodSecurity
public class SeguridadConfig {

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	LimitadorIntentosLogin limitadorLogin(SeguridadPropiedades propiedades, Clock reloj) {
		return new LimitadorIntentosLogin(propiedades.login(), reloj);
	}

	@Bean
	CookieCsrfTokenRepository csrfTokenRepository(SeguridadPropiedades propiedades) {
		CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
		var cookie = propiedades.cookie();
		repo.setCookieCustomizer(b -> b.secure(cookie.secure()).sameSite(cookie.sameSite()).path("/"));
		return repo;
	}

	@Bean
	SecurityFilterChain filtroSeguridad(HttpSecurity http, SeguridadPropiedades propiedades,
			CookieCsrfTokenRepository csrfRepo, CookieBearerTokenResolver resolver,
			PuntoEntradaJson puntoEntrada, ManejadorAccesoDenegadoJson accesoDenegado,
			JwtAuthenticationConverter convertidor, JwtDecoder decoder, VerificadorSesionVigente verificador,
			ManejadorFalloToken manejadorFalloToken) throws Exception {
		http
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.csrf(c -> c.spa().csrfTokenRepository(csrfRepo))
				.exceptionHandling(e -> e.authenticationEntryPoint(puntoEntrada).accessDeniedHandler(accesoDenegado))
				.addFilterBefore(filtroToken(decoder, convertidor, resolver, puntoEntrada, verificador,
						manejadorFalloToken), AuthorizationFilter.class)
				.authorizeHttpRequests(a -> a
						.requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/admin/login",
								"/api/auth/invitaciones/validar", "/api/auth/registro/invitacion").permitAll()
						// Cualquier sesion, incluso un ADMIN con MFA pendiente, puede ver su identidad y cerrar sesion.
						.requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
						.requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
						// Segundo factor: solo con el token de MFA pendiente (un ADMIN con sesion completa no pasa).
						.requestMatchers(HttpMethod.POST, "/api/auth/admin/mfa/**")
								.hasAuthority(JwtConfig.AUTORIDAD_MFA_PENDIENTE)
						.requestMatchers("/api/admin/**").hasRole("ADMIN")
						.requestMatchers("/api/familia/**").hasRole("FAMILIA")
						.requestMatchers("/error").permitAll()
						// Todo lo demas exige una sesion completa: el token con MFA pendiente no tiene ningun rol.
						.anyRequest().hasAnyRole("ADMIN", "FAMILIA"));

		List<String> origenes = propiedades.cors().origenesPermitidos();
		if (origenes.isEmpty()) {
			http.cors(AbstractHttpConfigurer::disable);
		} else {
			http.cors(c -> c.configurationSource(fuenteCors(origenes)));
		}
		return http.build();
	}

	/** Falla de autenticacion del filtro de bearer: 401 + cookie borrada, 503 sin tocar la cookie (REQ-XC-09). */
	@Bean
	ManejadorFalloToken manejadorFalloToken(PuntoEntradaJson puntoEntrada, CookieSesion cookieSesion,
			JsonMapper jsonMapper) {
		return new ManejadorFalloToken(puntoEntrada, cookieSesion, jsonMapper);
	}

	/**
	 * Filtro de bearer registrado a mano en lugar de oauth2ResourceServer(): ese DSL desactiva CSRF para toda
	 * peticion con token resuelto, y aca el token viaja en cookie, por lo que CSRF debe seguir activo.
	 * <p>
	 * El proveedor de JWT va decorado con la revalidacion central de la sesion (REQ-XC-09): el verificador es
	 * OBLIGATORIO (parametro sin alternativa), asi que si falta el bean la aplicacion no arranca en lugar de abrirse.
	 */
	private static BearerTokenAuthenticationFilter filtroToken(JwtDecoder decoder,
			JwtAuthenticationConverter convertidor, CookieBearerTokenResolver resolver,
			PuntoEntradaJson puntoEntrada, VerificadorSesionVigente verificador,
			ManejadorFalloToken manejadorFalloToken) {
		JwtAuthenticationProvider proveedorJwt = new JwtAuthenticationProvider(decoder);
		proveedorJwt.setJwtAuthenticationConverter(convertidor);
		BearerTokenAuthenticationFilter filtro = new BearerTokenAuthenticationFilter(
				new ProviderManager(new ProveedorJwtSesionVigente(proveedorJwt, verificador)));
		filtro.setBearerTokenResolver(resolver);
		filtro.setAuthenticationEntryPoint(puntoEntrada);
		filtro.setAuthenticationFailureHandler(manejadorFalloToken);
		return filtro;
	}

	private static CorsConfigurationSource fuenteCors(List<String> origenes) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(origenes);
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
		cors.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
		cors.setAllowCredentials(true);
		cors.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource fuente = new UrlBasedCorsConfigurationSource();
		fuente.registerCorsConfiguration("/api/**", cors);
		return fuente;
	}
}
