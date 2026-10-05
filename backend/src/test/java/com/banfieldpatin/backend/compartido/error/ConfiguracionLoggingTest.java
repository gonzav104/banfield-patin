package com.banfieldpatin.backend.compartido.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Lee el application.yml REAL (el del classpath principal, sin perfil de pruebas ni base de datos) por el mecanismo
 * ConfigData de Spring Boot y comprueba los niveles de log que fijan la ausencia de DNI/CUIL en los logs (REQ-DEP-10):
 * el logger JDBC de Hibernate APAGADO y los resolutores MVC que tratan excepciones en INFO (a DEBUG imprimen
 * "Resolved [<excepcion>]" con el mensaje completo, que en una violacion de PostgreSQL incluye los valores de la clave).
 * Los placeholders sin resolver de otras claves (DB_URL, JWT_SECRET) no se evaluan: solo se leen estas claves.
 */
class ConfiguracionLoggingTest {

	private static final String MVC = "org.springframework.web.servlet.mvc.";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer());

	@Test
	void elLoggerJdbcDeHibernateSigueApagadoYLosResolutoresMvcEstanEnInfoDeFormaExplicita() {
		runner.run(ctx -> {
			Map<String, String> niveles = Binder.get(ctx.getEnvironment())
					.bind("logging.level", Bindable.mapOf(String.class, String.class)).get();

			assertThat(niveles).containsEntry("org.hibernate.orm.jdbc.error", "OFF");
			assertThat(niveles).containsEntry(MVC + "method.annotation.ExceptionHandlerExceptionResolver", "INFO");
			assertThat(niveles).containsEntry(MVC + "annotation.ResponseStatusExceptionResolver", "INFO");
		});
	}

	@Test
	void ningunLoggerDeAplicacionNiRaizSeBajaADebugOTraceEnLaConfiguracionBase() {
		runner.run(ctx -> {
			Map<String, String> niveles = Binder.get(ctx.getEnvironment())
					.bind("logging.level", Bindable.mapOf(String.class, String.class)).get();

			assertThat(niveles).doesNotContainValue("DEBUG").doesNotContainValue("TRACE");
			assertThat(niveles).doesNotContainKey("root");
		});
	}

	@Test
	void losNombresDeLosLoggersFijadosSonClasesRealesDeSpringMvc() throws Exception {
		// Un nombre mal escrito en el YAML dejaria el logger real en DEBUG sin que nada lo notara.
		for (String nombre : new String[] { MVC + "method.annotation.ExceptionHandlerExceptionResolver",
				MVC + "annotation.ResponseStatusExceptionResolver" }) {
			assertThat(Class.forName(nombre)).isNotNull();
		}
	}
}
