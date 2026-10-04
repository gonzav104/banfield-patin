package com.banfieldpatin.backend.seguridad;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
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

	private final JwtEncoder encoder;
	private final SeguridadPropiedades propiedades;
	private final Clock reloj;

	public ServicioTokens(JwtEncoder encoder, SeguridadPropiedades propiedades, Clock reloj) {
		this.encoder = encoder;
		this.propiedades = propiedades;
		this.reloj = reloj;
	}

	/** Emite un JWT HS256 con claims minimos: sin email ni datos de contrasena. */
	public String emitir(UUID usuarioId, Rol rol, UUID escuelaId, UUID familiaId) {
		Instant ahora = reloj.instant();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(propiedades.jwt().emisor())
				.subject(usuarioId.toString())
				.issuedAt(ahora)
				.expiresAt(ahora.plus(propiedades.jwt().duracion()))
				.id(UUID.randomUUID().toString())
				.claim(CLAIM_ROL, rol.name())
				.claim(CLAIM_ESCUELA_ID, escuelaId.toString());
		if (rol == Rol.FAMILIA && familiaId != null) {
			claims.claim(CLAIM_FAMILIA_ID, familiaId.toString());
		}
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}
}
