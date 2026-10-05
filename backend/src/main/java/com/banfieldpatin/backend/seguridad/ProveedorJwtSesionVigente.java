package com.banfieldpatin.backend.seguridad;

import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Decorador del proveedor de JWT (REQ-XC-09, design 9.2): primero el delegado valida firma, vencimiento, emisor y
 * reclamos; solo entonces se consulta en la base si la sesion sigue vigente, en CADA autenticacion y sin cache.
 * <ul>
 * <li>vigente: devuelve el token del delegado;</li>
 * <li>no vigente o reclamos mal formados: {@link SesionNoVigenteException} (401 y cookie borrada);</li>
 * <li>{@link DataAccessException}: {@link AuthenticationServiceException} (503, cookie intacta, falla cerrado).</li>
 * </ul>
 * Los tokens con MFA pendiente tambien se verifican. Se usa solo cuando el resolver devolvio un token: las rutas
 * publicas y los anonimos nunca llegan aqui.
 */
public class ProveedorJwtSesionVigente implements AuthenticationProvider {

	private final AuthenticationProvider delegado;
	private final VerificadorSesionVigente verificador;

	public ProveedorJwtSesionVigente(AuthenticationProvider delegado, VerificadorSesionVigente verificador) {
		this.delegado = delegado;
		this.verificador = verificador;
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		Authentication resultado = delegado.authenticate(authentication);
		if (resultado == null) {
			return null;
		}
		if (!(resultado instanceof JwtAuthenticationToken token)) {
			throw new SesionNoVigenteException("Resultado de autenticacion inesperado.");
		}
		UsuarioAutenticado identidad;
		try {
			identidad = UsuarioAutenticado.desde(token.getToken());
		} catch (RuntimeException e) {
			throw new SesionNoVigenteException("Reclamos del token mal formados.", e);
		}
		boolean vigente;
		try {
			vigente = verificador.vigente(identidad.id(), identidad.escuelaId(), identidad.rol().name(),
					identidad.familiaId());
		} catch (DataAccessException e) {
			throw new AuthenticationServiceException("No se pudo verificar la vigencia de la sesion.", e);
		}
		if (!vigente) {
			throw new SesionNoVigenteException("La sesion ya no esta vigente.");
		}
		return resultado;
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return delegado.supports(authentication);
	}
}
