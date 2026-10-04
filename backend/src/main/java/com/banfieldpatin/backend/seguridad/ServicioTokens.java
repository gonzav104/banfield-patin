package com.banfieldpatin.backend.seguridad;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.banfieldpatin.backend.usuarios.Rol;

@Service
public class ServicioTokens {

	public static final String CLAIM_ROL = "rol";
	public static final String CLAIM_ESCUELA_ID = "escuela_id";
	public static final String CLAIM_FAMILIA_ID = "familia_id";
	/** Etapa del segundo factor de un ADMIN: obligatoria en sus tokens, ausente en los de FAMILIA. */
	public static final String CLAIM_MFA = "mfa";
	public static final String MFA_PENDIENTE = "PENDIENTE";
	public static final String MFA_COMPLETADA = "COMPLETADA";

	private final JwtEncoder encoder;
	private final SeguridadPropiedades propiedades;
	private final Clock reloj;

	public ServicioTokens(JwtEncoder encoder, SeguridadPropiedades propiedades, Clock reloj) {
		this.encoder = encoder;
		this.propiedades = propiedades;
		this.reloj = reloj;
	}

	/**
	 * Emite un JWT HS256 de SESION COMPLETA con claims minimos: sin email ni datos de contrasena. Para un ADMIN
	 * marca mfa=COMPLETADA, de modo que solo debe llamarse despues de verificar su segundo factor; el login por
	 * contrasena de un ADMIN usa {@link #emitirMfaPendiente}.
	 */
	public String emitir(UUID usuarioId, Rol rol, UUID escuelaId, UUID familiaId) {
		Instant ahora = reloj.instant();
		JwtClaimsSet.Builder claims = base(usuarioId, rol, escuelaId, ahora, propiedades.jwt().duracion());
		if (rol == Rol.FAMILIA && familiaId != null) {
			claims.claim(CLAIM_FAMILIA_ID, familiaId.toString());
		}
		if (rol == Rol.ADMIN) {
			claims.claim(CLAIM_MFA, MFA_COMPLETADA);
		}
		return codificar(claims);
	}

	/**
	 * Token de un ADMIN que ya mostro su contrasena pero no su segundo factor: mfa=PENDIENTE y vida corta
	 * (banfield.seguridad.mfa.duracion-pendiente). Solo sirve para enrolar, confirmar o verificar MFA, ver su
	 * identidad y cerrar sesion; no concede ROLE_ADMIN.
	 */
	public String emitirMfaPendiente(UUID usuarioId, UUID escuelaId) {
		Instant ahora = reloj.instant();
		return codificar(base(usuarioId, Rol.ADMIN, escuelaId, ahora, propiedades.mfa().duracionPendiente())
				.claim(CLAIM_MFA, MFA_PENDIENTE));
	}

	/** True si el JWT es de un ADMIN cuyo segundo factor aun no se completo. */
	public static boolean mfaPendiente(Jwt jwt) {
		return Rol.ADMIN.name().equals(jwt.getClaimAsString(CLAIM_ROL))
				&& !MFA_COMPLETADA.equals(jwt.getClaimAsString(CLAIM_MFA));
	}

	private JwtClaimsSet.Builder base(UUID usuarioId, Rol rol, UUID escuelaId, Instant ahora, Duration vigencia) {
		return JwtClaimsSet.builder()
				.issuer(propiedades.jwt().emisor())
				.subject(usuarioId.toString())
				.issuedAt(ahora)
				.expiresAt(ahora.plus(vigencia))
				.id(UUID.randomUUID().toString())
				.claim(CLAIM_ROL, rol.name())
				.claim(CLAIM_ESCUELA_ID, escuelaId.toString());
	}

	private String codificar(JwtClaimsSet.Builder claims) {
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}
}
