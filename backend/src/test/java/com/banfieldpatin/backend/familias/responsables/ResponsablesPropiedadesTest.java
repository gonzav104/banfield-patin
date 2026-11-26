package com.banfieldpatin.backend.familias.responsables;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** La bandera de altas es false por defecto y solo la habilita la variable de entorno con el nombre que realmente enlaza Spring. */
class ResponsablesPropiedadesTest {

	private static boolean habilitada(Map<String, Object> entorno) {
		var env = new StandardEnvironment();
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		env.getPropertySources().addLast(new SystemEnvironmentPropertySource(
				StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, entorno));
		return Binder.get(env).bind("banfield.responsables", ResponsablesPropiedades.class)
				.orElseGet(() -> new ResponsablesPropiedades(false)).altaHabilitada();
	}

	@Test
	void sinConfiguracionLasAltasQuedanDeshabilitadas() {
		assertThat(habilitada(Map.of())).isFalse();
	}

	@Test
	void laVariableDeEntornoDocumentadaEsLaQueHabilitaLaBandera() {
		assertThat(habilitada(Map.of("BANFIELD_RESPONSABLES_ALTAHABILITADA", "true"))).isTrue();
		assertThat(habilitada(Map.of("BANFIELD_RESPONSABLES_ALTAHABILITADA", "false"))).isFalse();
	}

	@Test
	void laVariableConGuionBajoEntreLasPalabrasDelScriptDeE2EYDeLaDocumentacionTambienEnlaza() {
		assertThat(habilitada(Map.of("BANFIELD_RESPONSABLES_ALTA_HABILITADA", "true"))).isTrue();
		assertThat(habilitada(Map.of("BANFIELD_RESPONSABLES_ALTA_HABILITADA", "false"))).isFalse();
	}
}
