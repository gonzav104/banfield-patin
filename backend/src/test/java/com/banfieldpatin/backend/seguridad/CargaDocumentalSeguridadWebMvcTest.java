package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.banfieldpatin.backend.usuarios.Rol;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.*;
import org.springframework.security.web.FilterChainProxy;

class CargaDocumentalSeguridadWebMvcTest extends BaseOpenApiWebMvc {
  @Autowired FilterChainProxy filtros;

  private String ruta() {
    return "/api/familia/deportistas/" + UUID.randomUUID() + "/documentos";
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  void multipartSinCabeceraCsrfNoLeeParametrosNiCuerpoAntesDeAutorizar(String metodo)
      throws Exception {
    var request =
        new MockHttpServletRequest(metodo, ruta()) {
          @Override
          public String getParameter(String name) {
            throw new AssertionError("No debe intentar resolver multipart sin cabecera CSRF");
          }
        };
    request.setServletPath(request.getRequestURI());
    request.setContentType("multipart/form-data; boundary=sintetico");
    request.setCookies(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "sintetico"));
    var response = new MockHttpServletResponse();
    filtros.doFilter(
        request,
        response,
        (q, s) -> {
          throw new AssertionError("No debe llegar al procesador");
        });
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("CSRF_INVALIDO");
  }

  @Test
  void sinHabilitacionExplicitaNoProcesaArchivos() throws Exception {
    verificador.reiniciar();
    var xsrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    assertThat(xsrf).isNotNull();
    mvc.perform(
            multipart(ruta())
                .file("archivo", "sintetico".getBytes())
                .param("tipo", "OTRO")
                .cookie(xsrf, sesion(Rol.FAMILIA))
                .header("X-XSRF-TOKEN", xsrf.getValue()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.codigo").value("CARGA_NO_DISPONIBLE"));
  }

  @Test
  void multipartFueraDelPostAutorizadoNoLlegaAlProcesador() throws Exception {
    verificador.reiniciar();
    var request = new MockHttpServletRequest("GET", ruta());
    request.setServletPath(request.getRequestURI());
    request.setContentType("multipart/form-data; boundary=sintetico");
    request.setCookies(sesion(Rol.FAMILIA));
    var response = new MockHttpServletResponse();
    filtros.doFilter(
        request,
        response,
        (q, r) -> {
          throw new AssertionError("No debe procesar archivos en la consulta GET");
        });
    assertThat(response.getStatus()).isEqualTo(415);
  }
}
