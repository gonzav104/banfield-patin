package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.seguridad.ManejadorAccesoDenegadoJson;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * La API de carga usa exclusivamente cabecera CSRF: impide que el fallback a parámetros lea
 * multipart sin autorización.
 */
public final class CsrfCargaFiltro extends OncePerRequestFilter {
  private final ManejadorAccesoDenegadoJson errores;

  public CsrfCargaFiltro(ManejadorAccesoDenegadoJson errores) {
    this.errores = errores;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if ((PreautorizacionCargaFiltro.esCarga(request)
            || PreautorizacionCargaFiltro.esMultipart(request)
                && !java.util.Set.of("GET", "HEAD", "OPTIONS", "TRACE")
                    .contains(request.getMethod()))
        && !StringUtils.hasText(request.getHeader("X-XSRF-TOKEN"))) {
      errores.handle(request, response, new MissingCsrfTokenException(null));
      return;
    }
    chain.doFilter(request, response);
  }
}
