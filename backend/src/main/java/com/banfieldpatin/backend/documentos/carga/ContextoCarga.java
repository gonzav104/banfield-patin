package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import java.time.Instant;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * No conserva el token ni lo serializa. Sólo se crea después de la autenticación de Spring
 * Security. {@code versionSesion} es la del token de ESTA solicitud (V19): cada paso de la carga
 * la exige junto con la cuenta activa, así que una sesión revocada mientras la carga transfiere no
 * publica aunque la cuenta se reactive antes del paso siguiente.
 */
record ContextoCarga(UsuarioAutenticado usuario, Instant expira, long versionSesion) {
  static ContextoCarga desde(Jwt jwt) {
    return new ContextoCarga(
        UsuarioAutenticado.desde(jwt), jwt.getExpiresAt(), ServicioTokens.versionSesion(jwt));
  }

  @Override
  public String toString() {
    return "ContextoCarga[privado]";
  }
}
