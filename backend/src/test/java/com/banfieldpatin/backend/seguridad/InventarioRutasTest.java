package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inventario estatico (sin contexto Spring ni base de datos) de las rutas HTTP de la aplicacion. Fija que
 * /api/auth/registro/invitacion es la unica ruta que crea usuarios y que no hay forma HTTP de crear un ADMIN
 * (REQ-INV-10, REQ-AUTH-13 S6). Una ruta mutante nueva obliga a actualizar este test de forma consciente.
 */
class InventarioRutasTest {

	/** Rutas mutantes esperadas, con el formato "METODO /ruta". */
	private static final Set<String> MUTANTES_ESPERADAS = Set.of(
			"POST /api/auth/login",
			"POST /api/auth/admin/login",
			"POST /api/auth/logout",
			"POST /api/auth/invitaciones/validar",
			"POST /api/auth/registro/invitacion",
			"POST /api/admin/invitaciones",
			"POST /api/admin/invitaciones/{id}/revocar");

	private static final Set<RequestMethod> MUTANTES = Set.of(RequestMethod.POST, RequestMethod.PUT,
			RequestMethod.PATCH, RequestMethod.DELETE);

	private static Set<String> rutas(boolean soloMutantes) throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider escaner = new ClassPathScanningCandidateComponentProvider(false);
		escaner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
		Set<String> rutas = new TreeSet<>();
		for (BeanDefinition definicion : escaner.findCandidateComponents("com.banfieldpatin.backend")) {
			Class<?> tipo = Class.forName(definicion.getBeanClassName());
			// Los controladores sonda de src/test no son parte de la aplicacion.
			if (tipo.getSimpleName().startsWith("ControladorSonda")) {
				continue;
			}
			RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(tipo, RequestMapping.class);
			String prefijo = base == null || base.path().length == 0 ? "" : base.path()[0];
			for (Method metodo : tipo.getDeclaredMethods()) {
				RequestMapping mapeo = AnnotatedElementUtils.findMergedAnnotation(metodo, RequestMapping.class);
				if (mapeo == null) {
					continue;
				}
				String sufijo = mapeo.path().length == 0 ? "" : mapeo.path()[0];
				for (RequestMethod verbo : mapeo.method()) {
					if (!soloMutantes || MUTANTES.contains(verbo)) {
						rutas.add(verbo + " " + prefijo + sufijo);
					}
				}
			}
		}
		return rutas;
	}

	@Test
	void lasRutasMutantesSonExactamenteLasEsperadas() throws Exception {
		assertThat(rutas(true)).containsExactlyInAnyOrderElementsOf(MUTANTES_ESPERADAS);
	}

	@Test
	void soloElRegistroPorInvitacionCreaUsuariosYNoExisteRegistroLibre() throws Exception {
		Set<String> todas = rutas(false);

		assertThat(todas).contains("POST /api/auth/registro/invitacion");
		assertThat(todas).noneMatch(r -> r.endsWith(" /api/auth/registro"));
		assertThat(todas.stream().filter(r -> r.contains("registro"))).containsExactly(
				"POST /api/auth/registro/invitacion");
	}

	@Test
	void ningunaRutaHttpCreaAdministradores() throws Exception {
		Set<String> mutantes = rutas(true);

		assertThat(mutantes).noneMatch(r -> r.contains("/usuarios") || r.contains("/administradores")
				|| r.contains("/admins") || r.contains("/bootstrap"));
		// Bajo /api/admin solo se emiten y revocan invitaciones.
		assertThat(mutantes.stream().filter(r -> r.contains("/api/admin/"))).containsExactlyInAnyOrder(
				"POST /api/admin/invitaciones", "POST /api/admin/invitaciones/{id}/revocar");
	}
}
