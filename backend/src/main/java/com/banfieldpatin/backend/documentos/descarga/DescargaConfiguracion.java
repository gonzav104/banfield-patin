package com.banfieldpatin.backend.documentos.descarga;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.documentos.almacen.AlmacenDocumentos;

/** La ruta existe siempre; sin Storage habilitado responde {@code 503 DESCARGA_NO_DISPONIBLE}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DescargaPropiedades.class)
public class DescargaConfiguracion {
	@Bean
	DescargaDocumentoService descargaDocumentoService(JdbcClient jdbc, PlatformTransactionManager tx,
			AuditoriaService auditoria, ObjectProvider<AlmacenDocumentos> almacenes, DescargaPropiedades propiedades) {
		return new DescargaDocumentoService(new ConsultaDescargaJdbc(jdbc), tx, auditoria, almacenes, propiedades);
	}
}
