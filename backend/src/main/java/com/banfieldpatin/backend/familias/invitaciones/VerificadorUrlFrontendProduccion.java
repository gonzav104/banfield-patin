package com.banfieldpatin.backend.familias.invitaciones;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Con el perfil prod la aplicacion no arranca sin una URL base https del frontend: sin ella los enlaces de invitacion
 * no se generarian o apuntarian a un origen no confiable.
 */
@Component
@Profile("prod")
class VerificadorUrlFrontendProduccion {

	VerificadorUrlFrontendProduccion(@Value("${" + UrlBaseFrontend.PROPIEDAD + ":}") String urlBase) {
		UrlBaseFrontend.exigirEnProduccion(urlBase);
	}
}
