package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.compartido.error.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Se instala después de AuthorizationFilter y antes del DispatcherServlet/multipart. No se registra
 * como filtro servlet duplicado.
 */
public final class PreautorizacionCargaFiltro extends OncePerRequestFilter {
  static final String CONTEXTO = PreautorizacionCargaFiltro.class.getName();
  private static final Pattern RUTA =
      Pattern.compile("^/api/familia/deportistas/([^/]+)/documentos$");
  private final ObjectProvider<CargaDocumentalService> proveedor;
  private final JsonMapper json;

  public PreautorizacionCargaFiltro(
      ObjectProvider<CargaDocumentalService> proveedor, JsonMapper json) {
    this.proveedor = proveedor;
    this.json = json;
  }

  private static String ruta(HttpServletRequest request) {
    return request
        .getRequestURI()
        .substring(request.getContextPath().length())
        .replaceAll(";[^/]*", "");
  }

  static boolean esCarga(HttpServletRequest request) {
    return "POST".equals(request.getMethod()) && RUTA.matcher(ruta(request)).matches();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var match = RUTA.matcher(ruta(request));
    if (!"POST".equals(request.getMethod()) || !match.matches()) {
      if (esMultipart(request)) {
        responder(
            response,
            new ExcepcionNegocio(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "TIPO_NO_SOPORTADO",
                "Este endpoint no procesa archivos."));
      } else {
        chain.doFilter(request, response);
      }
      return;
    }
    CargaDocumentalService servicio = proveedor.getIfAvailable();
    // Antes del cuerpo: seguridad y cuota de recepción (U6b). El permiso de procesamiento se toma
    // en el servicio, con el archivo ya recibido, para que un cliente lento no lo retenga (U6).
    boolean recibiendo = false;
    try {
      if (servicio == null) throw ErrorCarga.indisponible();
      if (!(SecurityContextHolder.getContext().getAuthentication()
          instanceof JwtAuthenticationToken auth))
        throw new ExcepcionNegocio(HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO", "Sesión requerida.");
      UUID atleta;
      try {
        atleta = UUID.fromString(match.group(1));
      } catch (IllegalArgumentException ex) {
        throw new ExcepcionNegocio(
            HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "Solicitud inválida.");
      }
      var contexto = ContextoCarga.desde(auth.getToken());
      servicio.preautorizar(contexto, atleta);
      // Con resolve-lazily el multipart solo se lee en el controlador: sin cuota, 429 sin cuerpo.
      if (!servicio.entrarRecepcion()) throw ErrorCarga.ocupada();
      recibiendo = true;
      request.setAttribute(CONTEXTO, contexto);
      chain.doFilter(request, response);
    } catch (ExcepcionNegocio ex) {
      responder(response, ex);
    } finally {
      if (recibiendo) servicio.salirRecepcion();
    }
  }

  static boolean esMultipart(HttpServletRequest request) {
    String tipo = request.getContentType();
    return tipo != null
        && tipo.stripLeading().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
  }

  private void responder(HttpServletResponse response, ExcepcionNegocio ex) throws IOException {
    response.setStatus(ex.getEstado().value());
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    json.writeValue(
        response.getOutputStream(),
        new ErrorRespuesta(ex.getCodigo(), ex.getMessage(), ex.getDetalles()));
  }
}
