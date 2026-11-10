package com.banfieldpatin.backend.documentos.carga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** La cuota de recepciones es independiente, configurable y nunca menor que el procesamiento. */
class RecepcionesCargaTest {
  @TempDir Path temporal;

  private CargaPropiedades enlazar(Map<String, String> valores) {
    var todos = new java.util.HashMap<>(valores);
    todos.put("banfield.documentos.carga.temporales", temporal.resolve("cargas").toString());
    return new Binder(new MapConfigurationPropertySource(todos))
        .bind("banfield.documentos.carga", CargaPropiedades.class)
        .get();
  }

  @Test
  void porDefectoAdmiteOchoRecepcionesYDosProcesamientos() {
    var p = enlazar(Map.of());
    assertThat(p.recepciones()).isEqualTo(8);
    assertThat(p.concurrencia()).isEqualTo(2);
  }

  @Test
  void laCuotaEsConfigurable() {
    assertThat(enlazar(Map.of("banfield.documentos.carga.recepciones", "4")).recepciones())
        .isEqualTo(4);
  }

  @Test
  void rechazaUnaCuotaMenorQueElProcesamientoOExcesiva() {
    assertThatThrownBy(
            () ->
                enlazar(
                    Map.of(
                        "banfield.documentos.carga.recepciones", "1",
                        "banfield.documentos.carga.concurrencia", "2")))
        .isInstanceOf(BindException.class);
    assertThatThrownBy(() -> enlazar(Map.of("banfield.documentos.carga.recepciones", "65")))
        .isInstanceOf(BindException.class);
    assertThatThrownBy(
            () ->
                new CargaPropiedades(
                    true, 10, temporal, 10, Duration.ofSeconds(1), Duration.ofSeconds(1),
                    "127.0.0.1", 3310, 2, Duration.ofMinutes(3), 0))
        .isInstanceOf(IllegalStateException.class);
  }
}
