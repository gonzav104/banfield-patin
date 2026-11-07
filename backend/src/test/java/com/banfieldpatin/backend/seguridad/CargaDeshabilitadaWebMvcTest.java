package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.documentos.carga.CargaDocumentalService;
import com.banfieldpatin.backend.usuarios.Rol;
import jakarta.servlet.http.Cookie;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Con la carga documental deshabilitada, los filtros HTTP y el manejador global no alteran los
 * endpoints anteriores. La referencia es el comportamiento observado en main antes de la carga
 * (64cf0ab), con las mismas solicitudes, en {@code compat/main-64cf0ab-carga-deshabilitada.txt}.
 */
@WebMvcTest(
    controllers = {
      ControladorSondaSeguridad.class,
      CsrfController.class,
      CargaDeshabilitadaWebMvcTest.Extra.class
    })
@Import({
  SeguridadConfig.class,
  JwtConfig.class,
  CookieSesion.class,
  CookieBearerTokenResolver.class,
  PuntoEntradaJson.class,
  ManejadorAccesoDenegadoJson.class,
  ManejadorGlobalErrores.class,
  ServicioTokens.class,
  RelojConfig.class,
  SesionVigenteDePrueba.class,
  CargaDeshabilitadaWebMvcTest.Extra.class
})
@ActiveProfiles("test")
class CargaDeshabilitadaWebMvcTest {
  @RestController
  static class Extra {
    @PostMapping("/api/familia/sonda/formulario")
    String formulario(@RequestParam("x") String x) {
      return "ok";
    }

    @PostMapping("/api/familia/sonda/cabecera")
    String cabecera(@RequestHeader("X-Requerida") String h) {
      return "ok";
    }
  }

  private static final Pattern FILA = Pattern.compile("^(\\w+)\\|([^|]+)\\|(\\w+)\\|(\\w+)\\|(\\w+)\\|(.+)$");
  private static final Pattern CODIGO = Pattern.compile("\"codigo\"\\s*:\\s*\"([A-Z_]+)\"");

  @Autowired MockMvc mvc;
  @Autowired ServicioTokens tokens;
  @Autowired ApplicationContext contexto;

  @Test
  void sinHabilitacionExplicitaNoExisteElServicioDeCarga() {
    assertThat(contexto.getBeanProvider(CargaDocumentalService.class).getIfAvailable()).isNull();
  }

  /**
   * JSON y errores de autenticación, autorización y CSRF son idénticos a main. Diferencias
   * intencionales: multipart que llegaría al procesador responde 415 (la API sólo procesa archivos
   * en la ruta de carga), y la ruta de carga existente pero deshabilitada responde 503 en lugar del
   * 404 de una ruta inexistente.
   */
  @Test
  void respuestasDeEndpointsAnterioresCoincidenConMain() throws Exception {
    String tabla;
    try (InputStream in =
        getClass().getResourceAsStream("/compat/main-64cf0ab-carga-deshabilitada.txt")) {
      tabla = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<String> diferencias = new ArrayList<>();
    int filas = 0;
    for (String linea : tabla.split("\n")) {
      var m = FILA.matcher(linea);
      assertThat(m.matches()).as(linea).isTrue();
      filas++;
      String metodo = m.group(1), ruta = m.group(2), tipo = m.group(3), auth = m.group(4);
      String csrf = m.group(5), enMain = m.group(6).trim();
      boolean carga = ruta.contains("{id}");
      ruta = ruta.replace("{id}", UUID.randomUUID().toString());
      String esperado = enMain;
      if (carga && auth.equals("FAMILIA") && csrf.equals("cabecera"))
        esperado = "503 CARGA_NO_DISPONIBLE";
      else if (tipo.equals("multipart") && !enMain.startsWith("401") && !enMain.startsWith("403"))
        esperado = "415 TIPO_NO_SOPORTADO";
      String observado = ejecutar(metodo, ruta, tipo, auth, csrf);
      if (!observado.equals(esperado))
        diferencias.add(linea + " -> observado: " + observado + " / esperado: " + esperado);
    }
    assertThat(filas).isEqualTo(162);
    assertThat(diferencias).isEmpty();
  }

  @Test
  void parametroObligatorioFaltanteConservaLaRespuestaAnterior() throws Exception {
    var x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    mvc.perform(
            post("/api/familia/sonda/formulario")
                .cookie(x, sesion(Rol.FAMILIA))
                .header("X-XSRF-TOKEN", x.getValue()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
        .andExpect(jsonPath("$.mensaje").value("La solicitud no es válida."));
  }

  /** Cambio intencional del manejador global: antes un 500 para un error del cliente. */
  @Test
  void cabeceraObligatoriaFaltanteEsUnErrorDeSolicitudYNoInterno() throws Exception {
    var x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    mvc.perform(
            post("/api/familia/sonda/cabecera")
                .cookie(x, sesion(Rol.FAMILIA))
                .header("X-XSRF-TOKEN", x.getValue()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
  }

  private Cookie sesion(Rol rol) {
    UUID familia = rol == Rol.FAMILIA ? UUID.randomUUID() : null;
    return new Cookie("BP_SESION", tokens.emitir(UUID.randomUUID(), rol, UUID.randomUUID(), familia));
  }

  private String ejecutar(String metodo, String ruta, String tipo, String auth, String csrf)
      throws Exception {
    var x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    AbstractMockHttpServletRequestBuilder<?> b;
    if (tipo.equals("multipart"))
      b =
          metodo.equals("POST")
              ? multipart(ruta).file("archivo", new byte[] {1, 2}).param("x", "1")
              : get(ruta).contentType("multipart/form-data; boundary=sintetico");
    else
      b =
          metodo.equals("POST")
              ? post(ruta).contentType(MediaType.APPLICATION_JSON).content("{}")
              : get(ruta);
    var cookies = new ArrayList<Cookie>();
    if (!csrf.equals("ninguno")) cookies.add(x);
    if (csrf.equals("cabecera")) b.header("X-XSRF-TOKEN", x.getValue());
    if (csrf.equals("soloParam")) b.param("_csrf", x.getValue());
    if (!auth.equals("ANON")) cookies.add(sesion(Rol.valueOf(auth)));
    if (!cookies.isEmpty()) b.cookie(cookies.toArray(new Cookie[0]));
    var resp = mvc.perform(b).andReturn().getResponse();
    String cuerpo = resp.getContentAsString();
    var c = CODIGO.matcher(cuerpo);
    return resp.getStatus()
        + " "
        + (c.find()
            ? c.group(1)
            : cuerpo.isEmpty() ? "-" : cuerpo.substring(0, Math.min(20, cuerpo.length())).replace('\n', ' '));
  }
}
