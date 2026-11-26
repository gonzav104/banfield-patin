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
	private static final Set<String> MUTANTES_ESPERADAS = Set.of("POST /api/familia/deportistas/{id}/documentos", "POST /api/familia/deportistas/{id}/documentos/{documentoId}/reemplazo", "POST /api/familia/tutores/{id}/documentos", "POST /api/familia/tutores/{id}/documentos/{documentoId}/reemplazo",
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
			// Tutores: alta, reemplazo completo y comandos C2-A de estado; sin DELETE ni PATCH.
			"POST /api/admin/familias/{familiaId}/tutores",
			"PUT /api/admin/tutores/{id}",
			"POST /api/admin/tutores/{id}/activar", "POST /api/admin/tutores/{id}/desactivar",
			"POST /api/admin/deportistas/{deportistaId}/vinculos-tutores",
			"PUT /api/admin/deportistas/{deportistaId}/vinculos-tutores/{vigenciaId}",
			"POST /api/admin/deportistas/{deportistaId}/vinculos-tutores/{vigenciaId}/revocar",
			// Deportistas (slice 4): alta, reemplazo completo y cambio de estado; sin DELETE ni PATCH.
			"POST /api/admin/deportistas",
			"PUT /api/admin/deportistas/{id}",
			"POST /api/admin/deportistas/{id}/activar",
			"POST /api/admin/deportistas/{id}/desactivar",
			"POST /api/admin/deportistas/{deportistaId}/documentos/{documentoId}/revision",
			// Revision de un DNI de un tutor (U19).
			"POST /api/admin/tutores/{tutorId}/documentos/{documentoId}/revision",
			// Vinculos (slice 5): lote de vinculacion, revocacion y cambio explicito de principal; sin DELETE ni PATCH.
			"POST /api/admin/familias/{familiaId}/deportistas",
			"POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/revocar",
			"POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/principal",
			// Temporadas (temp-1): alta y activacion; sin PUT, DELETE ni PATCH.
			"POST /api/admin/temporadas", "POST /api/admin/temporadas/{id}/activar",
			// Datos deportivos por temporada (temp-2): alta y reemplazo completo; sin DELETE ni PATCH.
			"POST /api/admin/deportistas/{deportistaId}/temporadas",
			"PUT /api/admin/deportistas/{deportistaId}/temporadas/{temporadaId}");

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
			// Ni los controladores sonda anidados en una clase de prueba (p. ej. SesionVigenteHttpDbTest.Sonda).
			if (tipo.getEnclosingClass() != null && tipo.getEnclosingClass().getSimpleName().endsWith("Test")) {
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
		// Bajo /api/admin se emiten y revocan invitaciones, se reinicia el MFA de otro ADMIN y se gestionan familias,
		// tutores, deportistas y vinculos (lista ampliada A PROPOSITO en los slices 2 a 5; cada slice siguiente la amplia de
		// forma consciente).
		assertThat(mutantes.stream().filter(r -> r.contains("/api/admin/"))).containsExactlyInAnyOrder(
				"POST /api/admin/invitaciones", "POST /api/admin/invitaciones/{id}/revocar",
				"POST /api/admin/usuarios/{id}/mfa/reiniciar",
				"POST /api/admin/familias", "PUT /api/admin/familias/{id}",
				"POST /api/admin/familias/{id}/activar", "POST /api/admin/familias/{id}/desactivar",
				"POST /api/admin/familias/{familiaId}/tutores", "PUT /api/admin/tutores/{id}",
				"POST /api/admin/tutores/{id}/activar", "POST /api/admin/tutores/{id}/desactivar",
				"POST /api/admin/deportistas", "PUT /api/admin/deportistas/{id}",
				"POST /api/admin/deportistas/{id}/activar", "POST /api/admin/deportistas/{id}/desactivar",
				"POST /api/admin/deportistas/{deportistaId}/documentos/{documentoId}/revision",
				"POST /api/admin/deportistas/{deportistaId}/vinculos-tutores",
				"PUT /api/admin/deportistas/{deportistaId}/vinculos-tutores/{vigenciaId}",
				"POST /api/admin/deportistas/{deportistaId}/vinculos-tutores/{vigenciaId}/revocar",
				"POST /api/admin/tutores/{tutorId}/documentos/{documentoId}/revision",
				"POST /api/admin/familias/{familiaId}/deportistas",
				"POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/revocar",
				"POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/principal",
				"POST /api/admin/temporadas", "POST /api/admin/temporadas/{id}/activar",
				"POST /api/admin/deportistas/{deportistaId}/temporadas",
				"PUT /api/admin/deportistas/{deportistaId}/temporadas/{temporadaId}");
	}

	@Test
	void laGestionDeFamiliasSoloTieneLasRutasMutantesPrevistasYNingunaBorra() throws Exception {
		Set<String> mutantes = rutas(true);

		assertThat(mutantes.stream().filter(r -> r.contains("/api/admin/familias"))).containsExactlyInAnyOrder(
				"POST /api/admin/familias", "PUT /api/admin/familias/{id}",
				"POST /api/admin/familias/{id}/activar", "POST /api/admin/familias/{id}/desactivar",
				"POST /api/admin/familias/{familiaId}/tutores", "POST /api/admin/familias/{familiaId}/deportistas",
				"POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/revocar",
				"POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/principal");
		assertThat(mutantes).noneMatch(r -> r.startsWith("DELETE ") || r.startsWith("PATCH "));
	}

	@Test
	void losTutoresSoloSeCreanBajoUnaFamiliaYSeReemplazanPorIdSinBorradoNiCambioDeFamilia() throws Exception {
		Set<String> mutantes = rutas(true);

		assertThat(mutantes.stream().filter(r -> r.contains("/tutores"))).containsExactlyInAnyOrder(
				"POST /api/admin/familias/{familiaId}/tutores", "PUT /api/admin/tutores/{id}",
				"POST /api/admin/tutores/{id}/activar", "POST /api/admin/tutores/{id}/desactivar",
				// Carga y reemplazo familiar de los DNI de un tutor (U19): no crean, borran ni cambian al tutor.
				"POST /api/familia/tutores/{id}/documentos", "POST /api/familia/tutores/{id}/documentos/{documentoId}/reemplazo",
				// Revision administrativa de un DNI de un tutor (U19): no crea, borra ni cambia al tutor.
				"POST /api/admin/tutores/{tutorId}/documentos/{documentoId}/revision");
		assertThat(rutas(false).stream().filter(r -> r.contains("/api/admin/tutores"))).containsExactlyInAnyOrder(
				"GET /api/admin/tutores/{id}", "PUT /api/admin/tutores/{id}", "GET /api/admin/tutores/{id}/documentos",
				"POST /api/admin/tutores/{id}/activar", "POST /api/admin/tutores/{id}/desactivar",
				"POST /api/admin/tutores/{tutorId}/documentos/{documentoId}/revision", "GET /api/admin/tutores/{tutorId}/documentos/{documentoId}/archivo");
		assertThat(mutantes).noneMatch(r -> r.startsWith("DELETE ") || r.startsWith("PATCH "));
	}

	@Test
	void losDeportistasSoloSeCreanReemplazanYCambianDeEstadoSinBorradoNiPatch() throws Exception {
		Set<String> mutantes = rutas(true);

		assertThat(mutantes.stream().filter(r -> r.contains("/api/admin/deportistas") && !r.contains("/temporadas")))
				.containsExactlyInAnyOrder("POST /api/admin/deportistas", "PUT /api/admin/deportistas/{id}",
						"POST /api/admin/deportistas/{id}/activar", "POST /api/admin/deportistas/{id}/desactivar",
						"POST /api/admin/deportistas/{deportistaId}/documentos/{documentoId}/revision",
						// Responsables (C2-B): alta idempotente, tipo declarado y revocación individual; sin borrado.
						"POST /api/admin/deportistas/{deportistaId}/vinculos-tutores",
						"PUT /api/admin/deportistas/{deportistaId}/vinculos-tutores/{vigenciaId}",
						"POST /api/admin/deportistas/{deportistaId}/vinculos-tutores/{vigenciaId}/revocar");
		assertThat(rutas(false).stream().filter(r -> r.contains("/api/admin/deportistas") && !r.contains("/temporadas")))
				.containsExactlyInAnyOrder("GET /api/admin/deportistas", "POST /api/admin/deportistas",
				"GET /api/admin/deportistas/{id}",
				"PUT /api/admin/deportistas/{id}", "POST /api/admin/deportistas/{id}/activar",
				"POST /api/admin/deportistas/{id}/desactivar", "GET /api/admin/deportistas/{deportistaId}/familias",
				"GET /api/admin/deportistas/{deportistaId}/vinculos", "GET /api/admin/deportistas/{id}/documentos",
				"GET /api/admin/deportistas/{deportistaId}/vinculos-tutores",
				"POST /api/admin/deportistas/{deportistaId}/vinculos-tutores",
				"PUT /api/admin/deportistas/{deportistaId}/vinculos-tutores/{vigenciaId}",
				"POST /api/admin/deportistas/{deportistaId}/vinculos-tutores/{vigenciaId}/revocar",
				// Checklist documental parcial (U20): lectura calculada, sin escritura.
				"GET /api/admin/deportistas/{id}/checklist-documental",
				// Descarga auditada de un documento (U16): lectura, sin escritura del documento.
				"GET /api/admin/deportistas/{deportistaId}/documentos/{documentoId}/archivo",
				"POST /api/admin/deportistas/{deportistaId}/documentos/{documentoId}/revision");
		assertThat(mutantes).noneMatch(r -> r.startsWith("DELETE ") || r.startsWith("PATCH "));
	}

	@Test
	void losVinculosSoloSeCreanRevocanYCambianDePrincipalPorLasTresRutasPrevistasSinBorradoNiPatch() throws Exception {
		Set<String> mutantes = rutas(true);

		assertThat(mutantes.stream().filter(r -> r.contains("/deportistas") && r.contains("/api/admin/familias/")))
				.containsExactlyInAnyOrder("POST /api/admin/familias/{familiaId}/deportistas",
						"POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/revocar",
						"POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/principal");
		assertThat(rutas(false).stream().filter(r -> r.startsWith("GET ") && r.contains("/deportistas")
				&& (r.contains("/familias/{familiaId}/") || r.endsWith("/familias"))))
				.containsExactlyInAnyOrder("GET /api/admin/familias/{familiaId}/deportistas",
						"GET /api/admin/deportistas/{deportistaId}/familias");
		// U11-A: la revision de los vinculos de un deportista se lee por una ruta GET propia (sin escritura).
		assertThat(rutas(false).stream().filter(r -> r.endsWith("/vinculos")))
				.containsExactly("GET /api/admin/deportistas/{deportistaId}/vinculos");
		assertThat(mutantes).noneMatch(r -> r.startsWith("DELETE ") || r.startsWith("PATCH "));
	}

	@Test
	void lasTemporadasSoloSeCreanYSeActivanSinReemplazoBorradoNiPatch() throws Exception {
		assertThat(rutas(true).stream().filter(r -> r.contains("/api/admin/temporadas") && !r.contains("/deportistas")))
				.containsExactlyInAnyOrder("POST /api/admin/temporadas", "POST /api/admin/temporadas/{id}/activar");
		assertThat(rutas(false).stream().filter(r -> r.contains("/api/admin/temporadas") && !r.contains("/deportistas")))
				.containsExactlyInAnyOrder(
				"GET /api/admin/temporadas", "POST /api/admin/temporadas", "GET /api/admin/temporadas/{id}",
				"POST /api/admin/temporadas/{id}/activar");
	}

	@Test
	void losDatosDeportivosPorTemporadaSoloSeCreanYSeReemplazanSinBorradoNiPatch() throws Exception {
		assertThat(rutas(true).stream().filter(r -> r.contains("/api/admin/") && r.contains("/temporadas") && r.contains("/deportistas")))
				.containsExactlyInAnyOrder("POST /api/admin/deportistas/{deportistaId}/temporadas",
						"PUT /api/admin/deportistas/{deportistaId}/temporadas/{temporadaId}");
		assertThat(rutas(false).stream().filter(r -> r.contains("/api/admin/") && r.contains("/temporadas") && r.contains("/deportistas")))
				.containsExactlyInAnyOrder("GET /api/admin/deportistas/{deportistaId}/temporadas",
						"POST /api/admin/deportistas/{deportistaId}/temporadas",
						"GET /api/admin/deportistas/{deportistaId}/temporadas/{temporadaId}",
						"PUT /api/admin/deportistas/{deportistaId}/temporadas/{temporadaId}",
						"GET /api/admin/temporadas/{temporadaId}/deportistas");
	}

	@Test
	void laUnicaRutaMutanteFamiliarEsLaCargaAutorizada() throws Exception {
		// La carga documental es la única excepción explícita al portal de lectura.
		assertThat(rutas(true).stream().filter(r -> r.contains(" /api/familia/") || r.endsWith(" /api/familia"))).containsExactly("POST /api/familia/deportistas/{id}/documentos", "POST /api/familia/deportistas/{id}/documentos/{documentoId}/reemplazo", "POST /api/familia/tutores/{id}/documentos", "POST /api/familia/tutores/{id}/documentos/{documentoId}/reemplazo");
	}

	@Test
	void elPortalConservaLasLecturasYAgregaLaFichaMinimaYLaCargaDocumental() throws Exception {
		// Slice 6: la lista COMPLETA de rutas bajo /api/familia (de cualquier metodo). Agregar una (aunque sea GET) obliga a
		// actualizar esta prueba, el contrato de docs/FAMILIAS-DEPORTISTAS.md y RutasDocumentadasTest de forma consciente.
		assertThat(rutas(false).stream().filter(r -> r.contains(" /api/familia"))).containsExactlyInAnyOrder(
				"GET /api/familia/mi-familia", "GET /api/familia/deportistas", "GET /api/familia/deportistas/{id}", "GET /api/familia/deportistas/{id}/documentos", "GET /api/familia/deportistas/{id}/checklist-documental", "GET /api/familia/tutores/{id}/documentos",
				// Datos deportivos anuales (temp-3): historial y detalle de un deportista propio.
				"GET /api/familia/deportistas/{deportistaId}/temporadas",
				"GET /api/familia/deportistas/{deportistaId}/temporadas/{temporadaId}",
				// Disponibilidad de la carga documental (U15): lectura de configuración, no escribe.
				"GET /api/familia/documentos/disponibilidad",
				// Ficha minima de un deportista propio (FE-04): datos generales y temporada vigente, sin datos sensibles.
				"GET /api/familia/deportistas/{deportistaId}/ficha", "GET /api/familia/deportistas/{deportistaId}/responsables", "POST /api/familia/deportistas/{id}/documentos",
				// Reemplazo de un DNI observado (U18): misma carga autorizada, ruta dedicada.
				"POST /api/familia/deportistas/{id}/documentos/{documentoId}/reemplazo",
				// Carga y reemplazo familiar de los DNI de un tutor (U19).
				"POST /api/familia/tutores/{id}/documentos", "POST /api/familia/tutores/{id}/documentos/{documentoId}/reemplazo");
	}
}
