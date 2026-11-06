package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import java.time.Instant;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * No conserva el token ni lo serializa. Sólo se crea después de la autenticación de Spring
 * Security.
 */
record ContextoCarga(UsuarioAutenticado usuario, Instant expira) {
  static ContextoCarga desde(Jwt jwt) {
    return new ContextoCarga(UsuarioAutenticado.desde(jwt), jwt.getExpiresAt());
  }

  @Override
  public String toString() {
    return "ContextoCarga[privado]";
  }
}
