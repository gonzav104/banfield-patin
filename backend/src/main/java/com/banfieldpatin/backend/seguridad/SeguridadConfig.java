package com.banfieldpatin.backend.seguridad;

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
			JwtAuthenticationConverter convertidor, JwtDecoder decoder) throws Exception {
		http
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.csrf(c -> c.spa().csrfTokenRepository(csrfRepo))
				.exceptionHandling(e -> e.authenticationEntryPoint(puntoEntrada).accessDeniedHandler(accesoDenegado))
				.addFilterBefore(filtroToken(decoder, convertidor, resolver, puntoEntrada),
						AuthorizationFilter.class)
				.authorizeHttpRequests(a -> a
						.requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/admin/login",
								"/api/auth/invitaciones/validar", "/api/auth/registro/invitacion").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
						.requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
						.requestMatchers("/api/admin/**").hasRole("ADMIN")
						.requestMatchers("/api/familia/**").hasRole("FAMILIA")
						.requestMatchers("/error").permitAll()
						.anyRequest().authenticated());

		List<String> origenes = propiedades.cors().origenesPermitidos();
		if (origenes.isEmpty()) {
			http.cors(AbstractHttpConfigurer::disable);
		} else {
			http.cors(c -> c.configurationSource(fuenteCors(origenes)));
		}
		return http.build();
	}

	/**
	 * Filtro de bearer registrado a mano en lugar de oauth2ResourceServer(): ese DSL desactiva CSRF para toda
	 * peticion con token resuelto, y aca el token viaja en cookie, por lo que CSRF debe seguir activo.
	 */
	private static BearerTokenAuthenticationFilter filtroToken(JwtDecoder decoder,
			JwtAuthenticationConverter convertidor, CookieBearerTokenResolver resolver,
			PuntoEntradaJson puntoEntrada) {
		JwtAuthenticationProvider proveedor = new JwtAuthenticationProvider(decoder);
		proveedor.setJwtAuthenticationConverter(convertidor);
		BearerTokenAuthenticationFilter filtro = new BearerTokenAuthenticationFilter(new ProviderManager(proveedor));
		filtro.setBearerTokenResolver(resolver);
		filtro.setAuthenticationEntryPoint(puntoEntrada);
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
