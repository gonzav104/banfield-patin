package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guarda de completitud que reemplaza los conteos de rutas escritos a mano: todo {@code @RestController} de la aplicacion debe
 * figurar en el contexto de pruebas de OpenAPI ({@link BaseOpenApiWebMvc}); si no, sus rutas no se mapearian y las pruebas de la
 * especificacion compararian contra un conjunto incompleto sin avisar. Sin contexto Spring ni base de datos.
 */
class ControladoresDeLaEspecificacionTest {

	private static Set<String> controladoresDeLaAplicacion() throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider escaner = new ClassPathScanningCandidateComponentProvider(false);
		escaner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
		Set<String> nombres = new TreeSet<>();
		for (BeanDefinition definicion : escaner.findCandidateComponents("com.banfieldpatin.backend")) {
			Class<?> tipo = Class.forName(definicion.getBeanClassName());
			// Las sondas de prueba (src/test) no son parte de la aplicacion, como en InventarioRutasTest.
			boolean deLaAplicacion = !tipo.getProtectionDomain().getCodeSource().getLocation().getPath()
					.endsWith("/test-classes/");
			if (deLaAplicacion) {
				nombres.add(tipo.getName());
			}
		}
		return nombres;
	}

	@Test
	void todoControladorDeLaAplicacionEstaEnElContextoDeLasPruebasDeOpenApi() throws Exception {
		Set<String> enElContexto = new TreeSet<>();
		for (Class<?> controlador : BaseOpenApiWebMvc.class.getAnnotation(WebMvcTest.class).controllers()) {
			enElContexto.add(controlador.getName());
		}

		Set<String> faltan = new TreeSet<>(controladoresDeLaAplicacion());
		faltan.removeAll(enElContexto);

		assertThat(controladoresDeLaAplicacion()).as("el escaneo debe encontrar los controladores").hasSizeGreaterThan(10);
		assertThat(faltan).as("agrega estos controladores a @WebMvcTest(controllers = ...) de BaseOpenApiWebMvc y un "
				+ "@MockitoBean de su servicio: si no, sus rutas no entran en las pruebas de la especificacion").isEmpty();
		assertThat(enElContexto).as("el contexto no debe nombrar controladores que ya no existen")
				.isSubsetOf(controladoresDeLaAplicacion());
	}
}
