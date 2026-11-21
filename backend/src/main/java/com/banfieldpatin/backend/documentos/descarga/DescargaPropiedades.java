package com.banfieldpatin.backend.documentos.descarga;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code concurrencia}: descargas administrativas simultáneas con el contenido en memoria (D1); sin
 * cola: la que no obtiene permiso responde {@code 429 DESCARGA_OCUPADA}. Independiente de la cuota de
 * la carga documental.
 */
@ConfigurationProperties("banfield.documentos.descarga")
public record DescargaPropiedades(@DefaultValue("2") int concurrencia) {
	public DescargaPropiedades {
		if (concurrencia < 1 || concurrencia > 8)
			throw new IllegalStateException("Configuración de descarga documental inválida");
	}
}
