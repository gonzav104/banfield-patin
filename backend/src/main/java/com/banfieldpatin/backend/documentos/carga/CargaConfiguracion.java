package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.documentos.almacen.AlmacenDocumentos;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.VerificadorSesionVigente;
import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "banfield.documentos.carga",
    name = "habilitada",
    havingValue = "true")
@EnableConfigurationProperties(CargaPropiedades.class)
public class CargaConfiguracion {
  @Bean
  CargaDocumentalService cargaDocumentalService(
      JdbcClient jdbc,
      PlatformTransactionManager tx,
      Clock reloj,
      VerificadorSesionVigente sesiones,
      DeportistaRepository deportistas,
      FamiliaDeportistaRepository vinculos,
      AuditoriaService auditoria,
      CargaPropiedades p,
      AlmacenDocumentos almacen,
      org.springframework.beans.factory.ObjectProvider<
              com.banfieldpatin.backend.documentos.almacen.StoragePropiedades>
          storage) {
    var s = storage.getIfAvailable();
    validarReserva(p, s == null ? null : s.plazoMaximoPorOperacion());
    return new CargaDocumentalService(
        new OperacionesCargaJdbc(jdbc, tx, reloj, sesiones, deportistas, vinculos, auditoria, p),
        p,
        almacen);
  }

  /**
   * La reserva debe cubrir cada tramo que se ejecuta sin renovarla: el escaneo (desde la reserva) y
   * una operación completa del adaptador de Storage (transferencia o inspección, hasta
   * 3 × (conexión + respuesta)). Así la reserva cubre los plazos configurados del cliente; ni su
   * vencimiento ni una ausencia observada prueban que el servidor no complete una escritura tardía.
   */
  static void validarReserva(CargaPropiedades p, java.time.Duration plazoStorage) {
    if (p.reserva().compareTo(p.scannerPlazo()) <= 0
        || plazoStorage != null && p.reserva().compareTo(plazoStorage) <= 0)
      throw new IllegalStateException("Configuración de carga documental inválida");
  }

  @Bean
  MultipartConfigElement multipartConfigElement(CargaPropiedades p) throws IOException {
    PreparadorDocumento.directorioPrivado(p.temporales());
    return new MultipartConfigElement(
        p.temporales().toString(), p.maxBytes(), p.maxBytes() + 65536, 0);
  }
}
