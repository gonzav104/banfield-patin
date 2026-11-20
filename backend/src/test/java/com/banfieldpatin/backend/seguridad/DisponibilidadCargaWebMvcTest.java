package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banfieldpatin.backend.documentos.carga.CargaDocumentalService;
import com.banfieldpatin.backend.documentos.carga.CargaPropiedades;
import com.banfieldpatin.backend.usuarios.Rol;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * La disponibilidad refleja la configuración de la carga (la misma condición que usa el
 * controlador de carga), no la salud del escáner ni de Storage. Solo FAMILIA la consulta.
 */
class DisponibilidadCargaWebMvcTest extends BaseOpenApiWebMvc {
  private static final String RUTA = "/api/familia/documentos/disponibilidad";

  @Test
  void sinSesionResponde401() throws Exception {
    verificador.reiniciar();
    mvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
  }

  @Test
  void unAdminRecibe403() throws Exception {
    verificador.reiniciar();
    mvc.perform(get(RUTA).cookie(sesion(Rol.ADMIN))).andExpect(status().isForbidden());
  }

  @Test
  void conLaConfiguracionPorDefectoLaCargaNoEstaHabilitada() throws Exception {
    verificador.reiniciar();
    var respuesta =
        mvc.perform(get(RUTA).cookie(sesion(Rol.FAMILIA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.habilitada").value(false))
            .andExpect(jsonPath("$.maxBytes").isEmpty())
            .andReturn()
            .getResponse();
    assertThat(respuesta.getHeader("Cache-Control")).contains("no-store");
    assertThat(respuesta.getContentAsString()).isEqualTo("{\"habilitada\":false,\"maxBytes\":null}");
  }

  @Nested
  @Import(Habilitada.Propiedades.class)
  class Habilitada {
    @MockitoBean CargaDocumentalService servicio;

    @TestConfiguration
    static class Propiedades {
      @Bean
      CargaPropiedades cargaPropiedades() {
        return new CargaPropiedades(
            true,
            4_000_000,
            Path.of(System.getProperty("java.io.tmpdir"), "disponibilidad-sin-uso"),
            16_000_000,
            Duration.ofSeconds(10),
            Duration.ofSeconds(20),
            "127.0.0.1",
            3310,
            2,
            Duration.ofMinutes(3));
      }
    }

    @Test
    void conLaCargaConfiguradaInformaElLimiteDeBytes() throws Exception {
      verificador.reiniciar();
      mvc.perform(get(RUTA).cookie(sesion(Rol.FAMILIA)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.habilitada").value(true))
          .andExpect(jsonPath("$.maxBytes").value(4_000_000));
    }
  }
}
