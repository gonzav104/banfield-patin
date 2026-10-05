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
			"POST /api/admin/invitaciones/{id}/revocar",
			"POST /api/auth/admin/mfa/enrolar",
			"POST /api/auth/admin/mfa/confirmar",
			"POST /api/auth/admin/mfa/verificar",
			"POST /api/admin/usuarios/{id}/mfa/reiniciar",
			// Gestion de familias (slice 2): alta, reemplazo completo y cambio de estado sin cascada.
			"POST /api/admin/familias",
			"PUT /api/admin/familias/{id}",
			"POST /api/admin/familias/{id}/activar",
			"POST /api/admin/familias/{id}/desactivar",
			// Tutores (slice 3): alta bajo la familia y reemplazo completo; sin DELETE ni PATCH.
			"POST /api/admin/familias/{familiaId}/tutores",
			"PUT /api/admin/tutores/{id}");

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
	void lasRutasDeSegundoFactorEstanBajoElPrefijoQueExigeTokenPendiente() throws Exception {
		assertThat(rutas(true).stream().filter(r -> r.contains("/mfa/") && !r.contains("{id}")))
				.allMatch(r -> r.startsWith("POST /api/auth/admin/mfa/"));
	}

	@Test
	void ningunaRutaHttpCreaAdministradores() throws Exception {
		Set<String> mutantes = rutas(true);

		// La unica ruta mutante bajo /usuarios es el reinicio de MFA (no crea ni modifica cuentas).
		assertThat(mutantes.stream().filter(r -> r.contains("/usuarios")))
				.containsExactly("POST /api/admin/usuarios/{id}/mfa/reiniciar");
		assertThat(mutantes).noneMatch(r -> r.contains("/administradores") || r.contains("/admins")
				|| r.contains("/bootstrap"));
		// Bajo /api/admin se emiten y revocan invitaciones, se reinicia el MFA de otro ADMIN y se gestionan familias
		// y tutores (lista ampliada A PROPOSITO en los slices 2 y 3; cada slice siguiente la amplia de forma consciente).
		assertThat(mutantes.stream().filter(r -> r.contains("/api/admin/"))).containsExactlyInAnyOrder(
				"POST /api/admin/invitaciones", "POST /api/admin/invitaciones/{id}/revocar",
				"POST /api/admin/usuarios/{id}/mfa/reiniciar",
				"POST /api/admin/familias", "PUT /api/admin/familias/{id}",
				"POST /api/admin/familias/{id}/activar", "POST /api/admin/familias/{id}/desactivar",
				"POST /api/admin/familias/{familiaId}/tutores", "PUT /api/admin/tutores/{id}");
	}

	@Test
	void laGestionDeFamiliasSoloTieneLasRutasMutantesPrevistasYNingunaBorra() throws Exception {
		Set<String> mutantes = rutas(true);

		assertThat(mutantes.stream().filter(r -> r.contains("/api/admin/familias"))).containsExactlyInAnyOrder(
				"POST /api/admin/familias", "PUT /api/admin/familias/{id}",
				"POST /api/admin/familias/{id}/activar", "POST /api/admin/familias/{id}/desactivar",
				"POST /api/admin/familias/{familiaId}/tutores");
		assertThat(mutantes).noneMatch(r -> r.startsWith("DELETE ") || r.startsWith("PATCH "));
	}

	@Test
	void losTutoresSoloSeCreanBajoUnaFamiliaYSeReemplazanPorIdSinBorradoNiCambioDeFamilia() throws Exception {
		Set<String> mutantes = rutas(true);

		assertThat(mutantes.stream().filter(r -> r.contains("/tutores"))).containsExactlyInAnyOrder(
				"POST /api/admin/familias/{familiaId}/tutores", "PUT /api/admin/tutores/{id}");
		assertThat(rutas(false).stream().filter(r -> r.contains("/api/admin/tutores"))).containsExactlyInAnyOrder(
				"GET /api/admin/tutores/{id}", "PUT /api/admin/tutores/{id}");
		assertThat(mutantes).noneMatch(r -> r.startsWith("DELETE ") || r.startsWith("PATCH "));
	}

	@Test
	void ningunaRutaMutanteExisteBajoElPortalDeFamilia() throws Exception {
		// FAMILIA es solo lectura (F5, REQ-XC-07): ninguna ruta POST/PUT/PATCH/DELETE bajo /api/familia/.
		assertThat(rutas(true)).noneMatch(r -> r.contains(" /api/familia/") || r.endsWith(" /api/familia"));
	}
}
