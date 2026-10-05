package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.banfieldpatin.backend.compartido.error.DetalleError;
import com.banfieldpatin.backend.compartido.error.ErrorRespuesta;

import tools.jackson.databind.JsonNode;

/**
 * RNF-14 / REQ-OAS-01: la especificacion generada por springdoc describe EXACTAMENTE las rutas {@code /api/**} que Spring MVC
 * mapea (leidas de {@code RequestMappingHandlerMapping}), con sus codigos de exito, el modelo de error compartido y los
 * esquemas de seguridad. Sin base de datos ni Docker: contexto con todos los controladores y servicios simulados.
 */
class OpenApiRutasTest extends BaseOpenApiWebMvc {

	/** Rutas que responden 201 (se verifican contra los controladores en OpenApiEstadosRealesTest). */
	static final Set<String> CREAN = Set.of("POST /api/admin/invitaciones", "POST /api/auth/registro/invitacion",
			"POST /api/admin/familias", "POST /api/admin/familias/{familiaId}/tutores", "POST /api/admin/deportistas");
	/** Rutas que responden 204 sin cuerpo. */
	static final Set<String> SIN_CONTENIDO = Set.of("POST /api/auth/logout", "POST /api/admin/usuarios/{id}/mfa/reiniciar");

	private static final Set<String> PUBLICAS = Set.of("GET /api/auth/csrf", "POST /api/auth/login",
			"POST /api/auth/admin/login", "POST /api/auth/invitaciones/validar", "POST /api/auth/registro/invitacion");

	@Test
	void cadaRutaApiMapeadaApareceEnLaEspecificacionConSuMetodoYNoSobraNinguna() throws Exception {
		Set<String> mapeadas = rutasDelMapeo();

		assertThat(mapeadas).as("el contexto debe mapear las rutas de todos los controladores").hasSize(39);
		assertThat(rutasDeLaEspecificacion(arbol())).containsExactlyInAnyOrderElementsOf(mapeadas);
	}

	@Test
	void todaRutaDeLaAplicacionTieneSuEntradaEnLaTablaDeRespuestasDeOpenApiConfig() {
		// Una ruta nueva obliga a decidir su codigo de exito y sus errores en OpenApiConfig.RUTAS (no hay valores por defecto).
		assertThat(OpenApiConfig.RUTAS.keySet()).containsExactlyInAnyOrderElementsOf(rutasDelMapeo());
	}

	@Test
	void lasVeinticuatroRutasDeFamiliasDeportistasYPortalDeLaDocumentacionEstanEnLaEspecificacion() throws Exception {
		Set<String> documentadas = RutasDocumentadasTest.rutasDeLaTabla();

		assertThat(documentadas).as("tabla de docs/FAMILIAS-DEPORTISTAS.md").hasSize(24);
		assertThat(rutasDeLaEspecificacion(arbol())).containsAll(documentadas);
	}

	@Test
	void elCodigoDeExitoDeCadaOperacionEsElDelControlador() throws Exception {
		JsonNode documento = arbol();
		Map<String, String> exitos = new TreeMap<>();
		documento.path("paths").properties().forEach(ruta -> ruta.getValue().properties().forEach(op -> {
			Set<String> dosXX = new TreeSet<>();
			op.getValue().path("responses").propertyNames().forEach(estado -> {
				if (estado.startsWith("2")) {
					dosXX.add(estado);
				}
			});
			exitos.put(op.getKey().toUpperCase() + " " + ruta.getKey(), String.join(",", dosXX));
		}));

		exitos.forEach((operacion, codigos) -> {
			String esperado = CREAN.contains(operacion) ? "201" : SIN_CONTENIDO.contains(operacion) ? "204" : "200";
			assertThat(codigos).as("codigo de exito de " + operacion).isEqualTo(esperado);
		});
		// Los 201 llevan Location (salvo el registro, que responde 201 sin Location); los 204 no tienen cuerpo.
		for (String operacion : CREAN) {
			assertThat(operacionDe(documento, operacion).path("responses").path("201").path("headers").has("Location"))
					.as("Location en " + operacion).isEqualTo(!operacion.equals("POST /api/auth/registro/invitacion"));
		}
		for (String operacion : SIN_CONTENIDO) {
			assertThat(operacionDe(documento, operacion).path("responses").path("204").has("content"))
					.as("204 sin cuerpo en " + operacion).isFalse();
		}
	}

	@Test
	void todaRespuestaDeErrorReferenciaErrorRespuestaYNingunaDeExito() throws Exception {
		JsonNode documento = arbol();
		int errores = 0;
		for (var ruta : documento.path("paths").properties()) {
			for (var op : ruta.getValue().properties()) {
				for (var respuesta : op.getValue().path("responses").properties()) {
					String ref = respuesta.getValue().path("content").path("application/json").path("schema")
							.path("$ref").asString("");
					if (respuesta.getKey().startsWith("2")) {
						assertThat(ref).as(op.getKey() + " " + ruta.getKey() + " " + respuesta.getKey())
								.isNotEqualTo("#/components/schemas/ErrorRespuesta");
					} else {
						errores++;
						assertThat(ref).as(op.getKey() + " " + ruta.getKey() + " " + respuesta.getKey())
								.isEqualTo("#/components/schemas/ErrorRespuesta");
					}
				}
			}
		}
		assertThat(errores).isGreaterThan(100);
	}

	@Test
	void elModeloDeErrorTieneLosCamposDeLosRecords() throws Exception {
		JsonNode esquemas = arbol().path("components").path("schemas");

		assertThat(nombres(esquemas.path("ErrorRespuesta").path("properties")))
				.containsExactlyInAnyOrderElementsOf(componentes(ErrorRespuesta.class)).containsExactlyInAnyOrder("codigo",
						"mensaje", "detalles");
		assertThat(esquemas.path("ErrorRespuesta").path("properties").path("detalles").path("items").path("$ref")
				.asString()).isEqualTo("#/components/schemas/DetalleError");
		assertThat(nombres(esquemas.path("DetalleError").path("properties")))
				.containsExactlyInAnyOrderElementsOf(componentes(DetalleError.class));
	}

	@Test
	void losCodigosDeErrorPropiosDeCadaRutaEstanEnSuDescripcion() throws Exception {
		JsonNode documento = arbol();

		assertThat(descripcion(documento, "POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/principal", "409"))
				.contains("FAMILIA_INACTIVA", "VINCULO_NO_ACTIVO", "VINCULO_PRINCIPAL_EN_CONFLICTO", "CONFLICTO_CONCURRENCIA");
		assertThat(descripcion(documento, "POST /api/admin/deportistas", "409")).contains("DNI_DUPLICADO",
				"DNI_RESERVADO_POR_INACTIVO", "CUIL_DUPLICADO");
		assertThat(descripcion(documento, "GET /api/familia/deportistas/{id}", "404")).contains("DEPORTISTA_NO_ENCONTRADO");
		assertThat(descripcion(documento, "POST /api/admin/familias", "400")).contains("VALIDACION", "SOLICITUD_INVALIDA");
		assertThat(descripcion(documento, "GET /api/admin/deportistas", "401")).contains("NO_AUTENTICADO");
		assertThat(descripcion(documento, "GET /api/admin/deportistas", "503")).contains("SERVICIO_NO_DISPONIBLE");
		assertThat(descripcion(documento, "POST /api/admin/deportistas", "403")).contains("ACCESO_DENEGADO", "CSRF_INVALIDO");
		assertThat(descripcion(documento, "GET /api/admin/deportistas", "403")).doesNotContain("CSRF_INVALIDO");
	}

	@Test
	void losEsquemasDeSeguridadSonLaCookieDeSesionYElHeaderCsrfRealesYSeAplicanSegunLaRuta() throws Exception {
		JsonNode documento = arbol();
		JsonNode esquemas = documento.path("components").path("securitySchemes");
		String cabeceraCsrf = new tools.jackson.databind.json.JsonMapper()
				.readTree(mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getContentAsString())
				.path("headerName").asString();

		assertThat(esquemas.path("sesionCookie").path("type").asString()).isEqualTo("apiKey");
		assertThat(esquemas.path("sesionCookie").path("in").asString()).isEqualTo("cookie");
		assertThat(esquemas.path("sesionCookie").path("name").asString()).isEqualTo("BP_SESION");
		assertThat(esquemas.path("csrfHeader").path("in").asString()).isEqualTo("header");
		assertThat(esquemas.path("csrfHeader").path("name").asString()).isEqualTo(cabeceraCsrf).isEqualTo("X-XSRF-TOKEN");

		for (String operacion : rutasDeLaEspecificacion(documento)) {
			Set<String> exigidos = new TreeSet<>();
			operacionDe(documento, operacion).path("security").forEach(r -> r.propertyNames().forEach(exigidos::add));
			boolean escribe = !operacion.startsWith("GET ");
			assertThat(exigidos.contains("sesionCookie")).as("sesion en " + operacion)
					.isEqualTo(!PUBLICAS.contains(operacion));
			assertThat(exigidos.contains("csrfHeader")).as("csrf en " + operacion).isEqualTo(escribe);
		}
	}

	@Test
	void elDocumentoDeclaraOpenApi31ConElTituloDeLaApi() throws Exception {
		JsonNode documento = arbol();

		assertThat(documento.path("openapi").asString()).startsWith("3.1.");
		assertThat(documento.path("info").path("title").asString()).isEqualTo("Banfield Patín Carrera - API");
	}

	private static JsonNode operacionDe(JsonNode documento, String operacion) {
		String[] partes = operacion.split(" ", 2);
		return documento.path("paths").path(partes[1]).path(partes[0].toLowerCase());
	}

	private static String descripcion(JsonNode documento, String operacion, String estado) {
		return operacionDe(documento, operacion).path("responses").path(estado).path("description").asString();
	}

	private static Set<String> nombres(JsonNode propiedades) {
		return new TreeSet<>(propiedades.propertyNames());
	}

	private static Set<String> componentes(Class<?> registro) {
		return new TreeSet<>(Arrays.stream(registro.getRecordComponents()).map(RecordComponent::getName).toList());
	}
}
