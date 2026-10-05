package com.banfieldpatin.backend.seguridad;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.deportistas.DeportistaAdminController;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.familias.FamiliaAdminController;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.FamiliaGestionAdminController;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.invitaciones.InvitacionAdminController;
import com.banfieldpatin.backend.familias.invitaciones.InvitacionAdminService;
import com.banfieldpatin.backend.familias.invitaciones.RegistroController;
import com.banfieldpatin.backend.familias.invitaciones.RegistroPorInvitacionService;
import com.banfieldpatin.backend.familias.invitaciones.ValidarInvitacionService;
import com.banfieldpatin.backend.familias.portal.FamiliaPortalController;
import com.banfieldpatin.backend.familias.portal.FamiliaPortalService;
import com.banfieldpatin.backend.familias.tutores.TutorAdminController;
import com.banfieldpatin.backend.familias.tutores.TutorAdminService;
import com.banfieldpatin.backend.familias.vinculos.VinculoAdminController;
import com.banfieldpatin.backend.familias.vinculos.VinculoAdminService;
import com.banfieldpatin.backend.usuarios.AutenticacionController;
import com.banfieldpatin.backend.usuarios.AutenticacionService;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.mfa.MfaAdminController;
import com.banfieldpatin.backend.usuarios.mfa.MfaController;
import com.banfieldpatin.backend.usuarios.mfa.MfaService;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contexto DB-free y Docker-free con TODOS los controladores de la aplicacion (y solo ellos: ninguna sonda de prueba),
 * la cadena de seguridad real, springdoc y los servicios simulados. Es el contexto que usan las pruebas de OpenAPI.
 * <p>
 * Springdoc no forma parte de la configuracion de un {@code @WebMvcTest}, asi que sus clases de autoconfiguracion se
 * importan las que declara su jar ({@link AutoconfiguracionSpringdoc}). Que el
 * contexto arranque asi, y que {@code /v3/api-docs} responda, es la comprobacion de compatibilidad de springdoc 3.1.1 con
 * Spring Boot 4.1.1 que no necesita base de datos; la del arranque completo esta en {@code OpenApiArranqueDbTest}.
 */
@WebMvcTest(controllers = { AutenticacionController.class, CsrfController.class, MfaController.class,
		MfaAdminController.class, FamiliaAdminController.class, FamiliaGestionAdminController.class,
		InvitacionAdminController.class, RegistroController.class, TutorAdminController.class,
		DeportistaAdminController.class, VinculoAdminController.class, FamiliaPortalController.class })
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class, ServicioTokens.class,
		RelojConfig.class, SesionVigenteDePrueba.class, OpenApiConfig.class, BaseOpenApiWebMvc.AutoconfiguracionSpringdoc.class })
@ActiveProfiles("test")
abstract class BaseOpenApiWebMvc {

	/**
	 * Importa, diferidas como cualquier autoconfiguracion, EXACTAMENTE las que declara el jar de springdoc en su
	 * {@code AutoConfiguration.imports} (sin listarlas a mano: las que aparezcan en otra version se toman solas). Van
	 * ordenadas porque las de {@code org.springdoc.webmvc} exigen ya registradas las de {@code org.springdoc.core}
	 * ({@code @ConditionalOnBean}) y este selector no aplica el orden de {@code @AutoConfiguration(after)}.
	 */
	static class AutoconfiguracionSpringdoc implements DeferredImportSelector {

		@Override
		public String[] selectImports(AnnotationMetadata metadata) {
			return ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates().stream()
					.filter(clase -> clase.startsWith("org.springdoc.")).sorted().toArray(String[]::new);
		}
	}

	protected static final String COOKIE = "BP_SESION";

	@Autowired
	protected MockMvc mvc;
	@Autowired
	protected ServicioTokens tokens;
	@Autowired
	protected SesionVigenteDePrueba.Verificador verificador;

	@Autowired
	protected RequestMappingHandlerMapping mapeoDeRutas;

	@MockitoBean
	protected AutenticacionService autenticacionService;
	@MockitoBean
	protected MfaService mfaService;
	@MockitoBean
	protected FamiliaRepository familiaRepository;
	@MockitoBean
	protected FamiliaAdminService familiaAdminService;
	@MockitoBean
	protected InvitacionAdminService invitacionAdminService;
	@MockitoBean
	protected ValidarInvitacionService validarInvitacionService;
	@MockitoBean
	protected RegistroPorInvitacionService registroPorInvitacionService;
	@MockitoBean
	protected TutorAdminService tutorAdminService;
	@MockitoBean
	protected DeportistaAdminService deportistaAdminService;
	@MockitoBean
	protected VinculoAdminService vinculoAdminService;
	@MockitoBean
	protected FamiliaPortalService familiaPortalService;

	protected Cookie sesion(Rol rol) {
		UUID familia = rol == Rol.FAMILIA ? UUID.randomUUID() : null;
		return new Cookie(COOKIE, tokens.emitir(UUID.randomUUID(), rol, UUID.randomUUID(), familia));
	}

	/** Un ADMIN con MFA pendiente: el token no tiene ningun rol. */
	protected Cookie sesionMfaPendiente() {
		return new Cookie(COOKIE, tokens.emitirMfaPendiente(UUID.randomUUID(), UUID.randomUUID()));
	}

	/** El documento OpenAPI tal como lo recibe un ADMIN con sesion completa. */
	protected String especificacion() throws Exception {
		return mvc.perform(get("/v3/api-docs").cookie(sesion(Rol.ADMIN))).andReturn().getResponse()
				.getContentAsString();
	}

	/** El documento OpenAPI como arbol JSON. */
	protected JsonNode arbol() throws Exception {
		return new JsonMapper().readTree(especificacion());
	}

	/**
	 * Todas las rutas {@code /api/**} que Spring MVC mapea (la fuente de verdad: {@link RequestMappingHandlerMapping}), con
	 * el formato "METODO /ruta". Es el mismo conjunto que recorre InventarioRutasTest, pero leido del mapeo real.
	 */
	protected Set<String> rutasDelMapeo() {
		Set<String> rutas = new TreeSet<>();
		mapeoDeRutas.getHandlerMethods().forEach((info, metodo) -> {
			Set<String> patrones = info.getPathPatternsCondition().getPatternValues();
			Set<RequestMethod> verbos = info.getMethodsCondition().getMethods();
			for (String patron : patrones) {
				if (patron.startsWith("/api/")) {
					verbos.forEach(verbo -> rutas.add(verbo + " " + patron));
				}
			}
		});
		return rutas;
	}

	/** Las operaciones del documento, con el formato "METODO /ruta". */
	protected static Set<String> rutasDeLaEspecificacion(JsonNode documento) {
		Set<String> rutas = new TreeSet<>();
		documento.path("paths").properties().forEach(ruta -> ruta.getValue().propertyNames()
				.forEach(metodo -> rutas.add(metodo.toUpperCase() + " " + ruta.getKey())));
		return rutas;
	}
}
