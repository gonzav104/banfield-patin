package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * REQ-XC-09 S1-S4, S9 / design 9.2 y 9.10: el decorador del proveedor de JWT consulta la vigencia en CADA autenticacion,
 * solo despues de que el delegado valido firma y vencimiento, sin cache y con las fallas de la base como 503.
 */
class ProveedorJwtSesionVigenteTest {

	private final UUID usuarioId = UUID.randomUUID();
	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();

	private AuthenticationProvider delegado;
	private VerificadorSesionVigente verificador;
	private ProveedorJwtSesionVigente proveedor;
	private final BearerTokenAuthenticationToken entrada = new BearerTokenAuthenticationToken("token-opaco");

	@BeforeEach
	void preparar() {
		delegado = mock(AuthenticationProvider.class);
		verificador = mock(VerificadorSesionVigente.class);
		proveedor = new ProveedorJwtSesionVigente(delegado, verificador);
	}

	private Jwt jwt(String sub, String rol, String escuela, String familia) {
		Jwt.Builder b = Jwt.withTokenValue("token-opaco").header("alg", "HS256").subject(sub)
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
		if (rol != null) {
			b.claim(ServicioTokens.CLAIM_ROL, rol);
		}
		if (escuela != null) {
			b.claim(ServicioTokens.CLAIM_ESCUELA_ID, escuela);
		}
		if (familia != null) {
			b.claim(ServicioTokens.CLAIM_FAMILIA_ID, familia);
		}
		return b.build();
	}

	private JwtAuthenticationToken autenticacion(Jwt jwt, String... autoridades) {
		List<GrantedAuthority> lista = java.util.Arrays.stream(autoridades)
				.<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
		return new JwtAuthenticationToken(jwt, lista);
	}

	private JwtAuthenticationToken delegaEmite(JwtAuthenticationToken resultado) {
		when(delegado.authenticate(entrada)).thenReturn(resultado);
		return resultado;
	}

	@Test
	void vigenteDevuelveElTokenDelDelegadoYConsultaConLosReclamosDelJwt() {
		JwtAuthenticationToken emitido = delegaEmite(autenticacion(
				jwt(usuarioId.toString(), "FAMILIA", escuelaId.toString(), familiaId.toString()), "ROLE_FAMILIA"));
		when(verificador.vigente(usuarioId, escuelaId, "FAMILIA", familiaId)).thenReturn(true);

		assertThat(proveedor.authenticate(entrada)).isSameAs(emitido);

		verify(verificador).vigente(usuarioId, escuelaId, "FAMILIA", familiaId);
	}

	@Test
	void adminSeConsultaConFamiliaNula() {
		delegaEmite(autenticacion(jwt(usuarioId.toString(), "ADMIN", escuelaId.toString(), null), "ROLE_ADMIN"));
		when(verificador.vigente(usuarioId, escuelaId, "ADMIN", null)).thenReturn(true);

		assertThat(proveedor.authenticate(entrada)).isNotNull();

		verify(verificador).vigente(usuarioId, escuelaId, "ADMIN", null);
	}

	@Test
	void noVigenteLanzaSesionNoVigente() {
		delegaEmite(autenticacion(jwt(usuarioId.toString(), "ADMIN", escuelaId.toString(), null), "ROLE_ADMIN"));
		when(verificador.vigente(usuarioId, escuelaId, "ADMIN", null)).thenReturn(false);

		assertThatThrownBy(() -> proveedor.authenticate(entrada)).isInstanceOf(SesionNoVigenteException.class);
	}

	@Test
	void fallaDeLaBaseSeConvierteEnAuthenticationServiceExceptionYNoEnSesionNoVigente() {
		delegaEmite(autenticacion(jwt(usuarioId.toString(), "ADMIN", escuelaId.toString(), null), "ROLE_ADMIN"));
		var causa = new DataAccessResourceFailureException("base caida");
		when(verificador.vigente(usuarioId, escuelaId, "ADMIN", null)).thenThrow(causa);

		assertThatThrownBy(() -> proveedor.authenticate(entrada))
				.isInstanceOf(AuthenticationServiceException.class)
				.isNotInstanceOf(SesionNoVigenteException.class)
				.hasCause(causa);
	}

	@Test
	void siElDelegadoRechazaElTokenSepropagaYNuncaSeConsultaLaBase() {
		var firmaInvalida = new BadCredentialsException("firma invalida");
		when(delegado.authenticate(entrada)).thenThrow(firmaInvalida);

		assertThatThrownBy(() -> proveedor.authenticate(entrada)).isSameAs(firmaInvalida);

		verifyNoInteractions(verificador);
	}

	@Test
	void reclamosMalformadosDanSesionNoVigenteSinConsultarLaBase() {
		String[][] casos = {
				{ "no-es-uuid", "ADMIN", escuelaId.toString(), null },
				{ usuarioId.toString(), "ADMIN", "no-es-uuid", null },
				{ usuarioId.toString(), "FAMILIA", escuelaId.toString(), "no-es-uuid" },
				{ usuarioId.toString(), "ROL_INEXISTENTE", escuelaId.toString(), null },
				{ usuarioId.toString(), null, escuelaId.toString(), null },
				{ usuarioId.toString(), "ADMIN", null, null } };
		for (String[] c : casos) {
			when(delegado.authenticate(entrada)).thenReturn(autenticacion(jwt(c[0], c[1], c[2], c[3]), "ROLE_X"));

			assertThatThrownBy(() -> proveedor.authenticate(entrada)).as(String.join("|", String.valueOf(c[1]), c[0]))
					.isInstanceOf(SesionNoVigenteException.class);
		}
		verifyNoInteractions(verificador);
	}

	@Test
	void unTokenConMfaPendienteTambienSeVerifica() {
		delegaEmite(autenticacion(jwt(usuarioId.toString(), "ADMIN", escuelaId.toString(), null),
				JwtConfig.AUTORIDAD_MFA_PENDIENTE));
		when(verificador.vigente(usuarioId, escuelaId, "ADMIN", null)).thenReturn(false);

		assertThatThrownBy(() -> proveedor.authenticate(entrada)).isInstanceOf(SesionNoVigenteException.class);

		verify(verificador).vigente(usuarioId, escuelaId, "ADMIN", null);
	}

	@Test
	void sinCacheDosAutenticacionesSonDosConsultas() {
		delegaEmite(autenticacion(jwt(usuarioId.toString(), "ADMIN", escuelaId.toString(), null), "ROLE_ADMIN"));
		when(verificador.vigente(usuarioId, escuelaId, "ADMIN", null)).thenReturn(true, false);

		assertThat(proveedor.authenticate(entrada)).isNotNull();
		assertThatThrownBy(() -> proveedor.authenticate(entrada)).isInstanceOf(SesionNoVigenteException.class);

		verify(verificador, times(2)).vigente(usuarioId, escuelaId, "ADMIN", null);
	}

	@Test
	void unResultadoQueNoEsUnTokenJwtFallaCerrado() {
		when(delegado.authenticate(entrada)).thenReturn(new BearerTokenAuthenticationToken("otro"));

		assertThatThrownBy(() -> proveedor.authenticate(entrada)).isInstanceOf(SesionNoVigenteException.class);

		verify(verificador, never()).vigente(any(), any(), any(), any());
	}

	@Test
	void soportaLoQueSoporteElDelegado() {
		when(delegado.supports(BearerTokenAuthenticationToken.class)).thenReturn(true);
		when(delegado.supports(String.class)).thenReturn(false);

		assertThat(proveedor.supports(BearerTokenAuthenticationToken.class)).isTrue();
		assertThat(proveedor.supports(String.class)).isFalse();
	}
}
