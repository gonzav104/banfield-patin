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
      AlmacenDocumentos almacen) {
    return new CargaDocumentalService(
        new OperacionesCargaJdbc(jdbc, tx, reloj, sesiones, deportistas, vinculos, auditoria, p),
        p,
        almacen);
  }

  @Bean
  MultipartConfigElement multipartConfigElement(CargaPropiedades p) throws IOException {
    PreparadorDocumento.directorioPrivado(p.temporales());
    return new MultipartConfigElement(
        p.temporales().toString(), p.maxBytes(), p.maxBytes() + 65536, 0);
  }
}
