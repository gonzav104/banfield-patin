package com.banfieldpatin.backend.documentos.almacen;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "banfield.documentos.storage", name = "habilitado", havingValue = "true")
@EnableConfigurationProperties(StoragePropiedades.class)
public class StorageConfiguracion {
	@Bean
	AlmacenDocumentos almacenDocumentos(StoragePropiedades propiedades) {
		return new SupabaseAlmacenDocumentos(propiedades);
	}
}
