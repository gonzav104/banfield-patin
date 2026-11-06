package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banfieldpatin.backend.usuarios.Rol;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Carga entre orígenes: Idempotency-Key sólo se admite desde orígenes configurados y CSRF sigue vigente. */
@TestPropertySource(properties = "banfield.seguridad.cors.origenes-permitidos=" + CorsCargaWebMvcTest.ORIGEN)
class CorsCargaWebMvcTest extends BaseOpenApiWebMvc {
  static final String ORIGEN = "https://app.ejemplo.test";

  private static String ruta() {
    return "/api/familia/deportistas/" + UUID.randomUUID() + "/documentos";
  }

  @Test
  void preflightDeCargaAdmiteIdempotencyKeyDesdeUnOrigenAutorizado() throws Exception {
    var r =
        mvc.perform(
                options(ruta())
                    .header("Origin", ORIGEN)
                    .header("Access-Control-Request-Method", "POST")
                    .header(
                        "Access-Control-Request-Headers",
                        "content-type,idempotency-key,x-xsrf-token"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", ORIGEN))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
            .andReturn();
    assertThat(r.getResponse().getHeader("Access-Control-Allow-Headers"))
        .containsIgnoringCase("idempotency-key")
        .containsIgnoringCase("x-xsrf-token");
  }

  @Test
  void preflightRechazaCabecerasNoAutorizadas() throws Exception {
    mvc.perform(
            options(ruta())
                .header("Origin", ORIGEN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-otra-cabecera"))
        .andExpect(status().isForbidden());
    // Con una cabecera admitida, Spring responde sólo las admitidas; el navegador bloquea el resto.
    var mixto =
        mvc.perform(
                options(ruta())
                    .header("Origin", ORIGEN)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "idempotency-key,x-otra-cabecera"))
            .andReturn();
    assertThat(mixto.getResponse().getHeader("Access-Control-Allow-Headers"))
        .containsIgnoringCase("idempotency-key")
        .doesNotContainIgnoringCase("x-otra-cabecera");
  }

  @Test
  void preflightRechazaOrigenesNoAutorizados() throws Exception {
    mvc.perform(
            options(ruta())
                .header("Origin", "https://otro.ejemplo.test")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "idempotency-key"))
        .andExpect(status().isForbidden());
  }

  @Test
  void origenAutorizadoNoEximeDeCsrf() throws Exception {
    var r =
        mvc.perform(
                multipart(ruta())
                    .file("archivo", new byte[] {1})
                    .param("tipo", "OTRO")
                    .header("Origin", ORIGEN)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .cookie(sesion(Rol.FAMILIA)))
            .andExpect(status().isForbidden())
            .andReturn();
    assertThat(r.getResponse().getContentAsString()).contains("CSRF_INVALIDO");
  }
}
