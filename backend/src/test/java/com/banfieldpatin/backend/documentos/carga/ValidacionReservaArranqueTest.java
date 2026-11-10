package com.banfieldpatin.backend.documentos.carga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.documentos.almacen.AlmacenDocumentos;
import com.banfieldpatin.backend.documentos.almacen.StoragePropiedades;
import com.banfieldpatin.backend.documentos.almacen.StorageConfiguracion;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.VerificadorSesionVigente;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * La reserva se valida al ARRANCAR el contexto de Spring (no solo en el validador estático): debe
 * superar el escaneo y 3 × (conexión + respuesta) de Storage. Con los plazos de Storage de esta
 * prueba (3 s + 15 s) el piso es de 54 s: 36 s (2×) ya no alcanza.
 */
class ValidacionReservaArranqueTest {
  @TempDir Path temporal;

  private ApplicationContextRunner contexto(String... propiedades) {
    return new ApplicationContextRunner()
        .withUserConfiguration(CargaConfiguracion.class, StorageConfiguracion.class)
        .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(VerificadorSesionVigente.class, () -> mock(VerificadorSesionVigente.class))
        .withBean(DeportistaRepository.class, () -> mock(DeportistaRepository.class))
        .withBean(FamiliaDeportistaRepository.class, () -> mock(FamiliaDeportistaRepository.class))
        .withBean(AuditoriaService.class, () -> mock(AuditoriaService.class))
        .withPropertyValues(
            "banfield.documentos.carga.habilitada=true",
            "banfield.documentos.carga.temporales=" + temporal.resolve("cargas"),
            "banfield.documentos.carga.scanner-plazo=20s")
        // Las propiedades de cada prueba se aplican después y prevalecen sobre las base.
        .withPropertyValues(propiedades);
  }

  private String[] conStorage(String reserva) {
    return new String[] {
      "banfield.documentos.storage.habilitado=true",
      "banfield.documentos.storage.url=http://127.0.0.1:1",
      "banfield.documentos.storage.bucket=privado",
      "banfield.documentos.storage.credencial=credencial-sintetica",
      "banfield.documentos.storage.conexion=3s",
      "banfield.documentos.storage.respuesta=15s",
      "banfield.documentos.carga.reserva=" + reserva
    };
  }

  @Test
  void conStorageHabilitadoUnaReservaPorEncimaDeTresVecesElPlazoArranca() {
    contexto(conStorage("55s"))
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c).hasSingleBean(CargaDocumentalService.class);
              assertThat(c).hasSingleBean(StoragePropiedades.class);
              assertThat(c).hasSingleBean(AlmacenDocumentos.class);
            });
  }

  @Test
  void conStorageHabilitadoUnaReservaIgualOInferiorATresVecesElPlazoImpideArrancar() {
    // 54 s es exactamente 3 × 18 s: no alcanza; 40 s supera 2 × 18 s pero no 3 ×; 21 s apenas
    // supera el escaneo.
    for (String reserva : new String[] {"54s", "40s", "36s", "21s"})
      contexto(conStorage(reserva))
          .run(
              c -> {
                assertThat(c).as("reserva " + reserva).hasFailed();
                assertThat(c.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Configuración de carga documental inválida");
              });
  }

  @Test
  void sinStorageSoloSeExigeSuperarElEscaneo() {
    contexto(
            "banfield.documentos.storage.habilitado=false",
            "banfield.documentos.carga.reserva=21s")
        .withBean(AlmacenDocumentos.class, () -> mock(AlmacenDocumentos.class))
        .run(c -> assertThat(c).hasNotFailed().hasSingleBean(CargaDocumentalService.class));
    contexto(
            "banfield.documentos.storage.habilitado=false",
            "banfield.documentos.carga.reserva=20s")
        .withBean(AlmacenDocumentos.class, () -> mock(AlmacenDocumentos.class))
        .run(c -> assertThat(c).hasFailed());
  }

  @Test
  void conLaIntegracionDeshabilitadaNoSeValidaNiSeCreaNada() {
    contexto("banfield.documentos.carga.habilitada=false", "banfield.documentos.carga.reserva=1s")
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c).doesNotHaveBean(CargaDocumentalService.class);
            });
  }
}
