package com.banfieldpatin.backend.seguridad;

import static java.util.Map.entry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.banfieldpatin.backend.compartido.error.DetalleError;
import com.banfieldpatin.backend.compartido.error.ErrorRespuesta;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Contrato OpenAPI (RNF-14) generado por springdoc a partir de los controladores. Esta clase solo agrega lo que el codigo
 * no puede inferir y NO cambia ninguna regla de autorizacion, CSRF ni sesion (eso sigue en {@link SeguridadConfig}):
 * <ul>
 * <li>esquemas de seguridad: la cookie de sesion y el header CSRF;</li>
 * <li>el modelo de error compartido {@link ErrorRespuesta} ({@code {codigo, mensaje, detalles}});</li>
 * <li>por operacion: el codigo de exito real (201 y 204; springdoc documenta {@code ResponseEntity} como 200), los
 * errores propios de la ruta y los transversales (401, 403, 503, 400), y un {@code operationId} estable.</li>
 * </ul>
 * La tabla {@link #RUTAS} es el unico lugar donde se escriben a mano los codigos; {@code OpenApiRutasTest} exige que cada
 * ruta {@code /api/**} de la aplicacion figure en ella y que los 201 coincidan con los controladores, de modo que no se
 * desactualice en silencio. Los ejemplos se omiten a proposito: ningun dato con apariencia real (DNI, email, token).
 */
@Configuration
public class OpenApiConfig {

	/** Cookie HttpOnly con el JWT (el nombre sale de la configuracion). */
	public static final String ESQUEMA_SESION = "sesionCookie";
	/** Header que repite el valor de la cookie XSRF-TOKEN en toda solicitud que escribe. */
	public static final String ESQUEMA_CSRF = "csrfHeader";
	static final String CABECERA_CSRF = "X-XSRF-TOKEN";
	static final String ESQUEMA_ERROR = "ErrorRespuesta";

	/** Rutas sin sesion (la cadena las deja pasar); las que escriben siguen exigiendo CSRF. */
	private static final Set<String> PUBLICAS = Set.of("GET /api/auth/csrf", "POST /api/auth/login",
			"POST /api/auth/admin/login", "POST /api/auth/invitaciones/validar", "POST /api/auth/registro/invitacion");

	/** Cualquier sesion vale, incluso un ADMIN con MFA pendiente. */
	private static final Set<String> CUALQUIER_SESION = Set.of("GET /api/auth/me", "POST /api/auth/logout");

	/**
	 * Codigo de exito (200 por defecto) y errores propios de cada ruta, con el formato {@code "ESTADO CODIGO"}. Los errores
	 * transversales (401/403/503, 400 por cuerpo o parametros) se agregan aparte segun la politica de la ruta.
	 */
	static final Map<String, Ruta> RUTAS = Map.ofEntries(
			// --- Autenticacion y segundo factor
			entry("GET /api/auth/csrf", ruta(200)),
			entry("POST /api/auth/login", ruta(200, "401 CREDENCIALES_INVALIDAS")),
			entry("POST /api/auth/admin/login", ruta(200, "401 CREDENCIALES_INVALIDAS")),
			entry("POST /api/auth/logout", ruta(204)),
			entry("GET /api/auth/me", ruta(200)),
			entry("POST /api/auth/admin/mfa/enrolar", ruta(200, "409 MFA_ESTADO_INVALIDO")),
			entry("POST /api/auth/admin/mfa/confirmar", ruta(200, "401 CODIGO_MFA_INVALIDO", "409 MFA_ESTADO_INVALIDO")),
			entry("POST /api/auth/admin/mfa/verificar", ruta(200, "401 CODIGO_MFA_INVALIDO", "409 MFA_ESTADO_INVALIDO")),
			entry("POST /api/admin/usuarios/{id}/mfa/reiniciar",
					ruta(204, "403 MFA_AUTOREINICIO_NO_PERMITIDO", "404 USUARIO_NO_ENCONTRADO")),
			// --- Invitaciones y registro
			entry("POST /api/auth/invitaciones/validar", ruta(200, "400 INVITACION_NO_DISPONIBLE")),
			entry("POST /api/auth/registro/invitacion",
					ruta(201, "400 INVITACION_NO_DISPONIBLE", "409 EMAIL_YA_REGISTRADO")),
			entry("POST /api/admin/invitaciones", creada("404 FAMILIA_NO_ENCONTRADA")),
			entry("GET /api/admin/invitaciones", ruta(200)),
			entry("GET /api/admin/invitaciones/{id}", ruta(200, "404 INVITACION_NO_ENCONTRADA")),
			entry("POST /api/admin/invitaciones/{id}/revocar",
					ruta(200, "404 INVITACION_NO_ENCONTRADA", "409 INVITACION_NO_REVOCABLE")),
			// --- Familias
			entry("GET /api/admin/familias", ruta(200)),
			entry("GET /api/admin/familias/listado", ruta(200)),
			entry("POST /api/admin/familias", creada()),
			entry("GET /api/admin/familias/{id}", ruta(200, "404 FAMILIA_NO_ENCONTRADA")),
			entry("PUT /api/admin/familias/{id}", ruta(200, "404 FAMILIA_NO_ENCONTRADA")),
			entry("POST /api/admin/familias/{id}/activar", ruta(200, "404 FAMILIA_NO_ENCONTRADA")),
			entry("POST /api/admin/familias/{id}/desactivar", ruta(200, "404 FAMILIA_NO_ENCONTRADA")),
			// --- Tutores
			entry("POST /api/admin/familias/{familiaId}/tutores",
					creada("404 FAMILIA_NO_ENCONTRADA", "409 FAMILIA_INACTIVA")),
			entry("GET /api/admin/tutores/{id}", ruta(200, "404 TUTOR_NO_ENCONTRADO")),
			entry("PUT /api/admin/tutores/{id}", ruta(200, "404 TUTOR_NO_ENCONTRADO", "409 FAMILIA_INACTIVA")),
			// --- Deportistas
			entry("GET /api/admin/deportistas", ruta(200)),
			entry("POST /api/admin/deportistas",
					creada("409 DNI_DUPLICADO", "409 DNI_RESERVADO_POR_INACTIVO", "409 CUIL_DUPLICADO")),
			entry("GET /api/admin/deportistas/{id}", ruta(200, "404 DEPORTISTA_NO_ENCONTRADO")),
			entry("PUT /api/admin/deportistas/{id}", ruta(200, "404 DEPORTISTA_NO_ENCONTRADO", "409 DNI_DUPLICADO",
					"409 DNI_RESERVADO_POR_INACTIVO", "409 CUIL_DUPLICADO")),
			entry("POST /api/admin/deportistas/{id}/activar", ruta(200, "404 DEPORTISTA_NO_ENCONTRADO")),
			entry("POST /api/admin/deportistas/{id}/desactivar", ruta(200, "404 DEPORTISTA_NO_ENCONTRADO")),
			// --- Vinculos
			entry("GET /api/admin/familias/{familiaId}/deportistas", ruta(200, "404 FAMILIA_NO_ENCONTRADA")),
			entry("GET /api/admin/deportistas/{deportistaId}/familias", ruta(200, "404 DEPORTISTA_NO_ENCONTRADO")),
			entry("POST /api/admin/familias/{familiaId}/deportistas",
					ruta(200, "404 FAMILIA_NO_ENCONTRADA", "404 DEPORTISTA_NO_ENCONTRADO", "409 FAMILIA_INACTIVA",
							"409 DEPORTISTA_INACTIVO", "409 VINCULO_PRINCIPAL_EN_CONFLICTO", "409 CONFLICTO_CONCURRENCIA")),
			entry("POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/revocar",
					ruta(200, "404 VINCULO_NO_ENCONTRADO", "409 VINCULO_NO_ACTIVO", "409 CONFLICTO_CONCURRENCIA")),
			entry("POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/principal",
					ruta(200, "404 FAMILIA_NO_ENCONTRADA", "404 VINCULO_NO_ENCONTRADO", "409 FAMILIA_INACTIVA",
							"409 VINCULO_NO_ACTIVO", "409 VINCULO_PRINCIPAL_EN_CONFLICTO", "409 CONFLICTO_CONCURRENCIA")),
			// --- Portal de FAMILIA (solo lectura)
			entry("GET /api/familia/mi-familia", ruta(200, "404 FAMILIA_NO_ENCONTRADA")),
			entry("GET /api/familia/deportistas", ruta(200)),
			entry("GET /api/familia/deportistas/{id}", ruta(200, "404 DEPORTISTA_NO_ENCONTRADO")));

	/** Respuestas con un secreto de un solo uso: nunca se cachean y no se vuelven a mostrar. */
	private static final Map<String, String> DESCRIPCIONES = Map.of(
			"POST /api/admin/invitaciones",
			"Crea una invitacion. La respuesta es la UNICA que contiene el token en claro (se muestra una sola vez; "
					+ "solo se guarda su hash) y lleva Cache-Control: no-store.",
			"POST /api/auth/admin/mfa/enrolar",
			"Enrola el TOTP del ADMIN. El secreto y la URI otpauth se devuelven una sola vez (Cache-Control: no-store) "
					+ "y no se registran en logs ni auditoria. Solo con el token de MFA pendiente.");

	/** Codigo de exito y errores propios de una ruta. */
	record Ruta(int exito, boolean location, List<String> errores) {
	}

	private static Ruta ruta(int exito, String... errores) {
		return new Ruta(exito, false, List.of(errores));
	}

	/** 201 con el header Location del recurso creado (el registro por invitacion responde 201 SIN Location). */
	private static Ruta creada(String... errores) {
		return new Ruta(201, true, List.of(errores));
	}

	@Bean
	OpenAPI contratoBanfieldPatin(CookieSesion cookieSesion) {
		return new OpenAPI()
				.info(new Info().title("Banfield Patín Carrera - API").version("1.0")
						.description("Contrato de la API REST del backend. Errores: siempre {codigo, mensaje, detalles}. "
								+ "Una ruta con otro metodo HTTP responde 405 METODO_NO_PERMITIDO (no se lista por operacion)."))
				.schemaRequirement(ESQUEMA_SESION, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
						.in(SecurityScheme.In.COOKIE).name(cookieSesion.nombre())
						.description("JWT en cookie HttpOnly; se obtiene con los POST de login (o el segundo factor)."))
				.schemaRequirement(ESQUEMA_CSRF, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
						.in(SecurityScheme.In.HEADER).name(CABECERA_CSRF)
						.description("Valor de la cookie XSRF-TOKEN (se emite con GET /api/auth/csrf). "
								+ "Obligatorio en toda ruta que escribe."));
	}

	/** Un {@code operationId} legible y estable (springdoc agrega sufijos numericos a los nombres repetidos). */
	@Bean
	OperationCustomizer identificadorEstable() {
		return (operacion, metodo) -> {
			String controlador = metodo.getBeanType().getSimpleName().replaceFirst("Controller$", "");
			operacion.setOperationId(controlador + "_" + metodo.getMethod().getName());
			return operacion;
		};
	}

	@Bean
	OpenApiCustomizer politicaDeRutasYErrores() {
		return openApi -> {
			registrarModeloDeError(openApi);
			if (openApi.getPaths() == null) {
				return;
			}
			openApi.getPaths().forEach((ruta, item) -> item.readOperationsMap()
					.forEach((metodo, operacion) -> personalizar(metodo.name() + " " + ruta, operacion)));
		};
	}

	private static void registrarModeloDeError(OpenAPI openApi) {
		if (openApi.getComponents() == null) {
			openApi.setComponents(new Components());
		}
		Map<String, Schema> modelo = new LinkedHashMap<>(ModelConverters.getInstance(true).readAll(ErrorRespuesta.class));
		modelo.putAll(ModelConverters.getInstance(true).readAll(DetalleError.class));
		modelo.forEach((nombre, esquema) -> openApi.getComponents().addSchemas(nombre, esquema));
	}

	private static void personalizar(String clave, Operation operacion) {
		Ruta ruta = RUTAS.get(clave);
		if (ruta == null) {
			// Una ruta nueva sin entrada en la tabla se documenta con lo inferido; OpenApiRutasTest la rechaza.
			return;
		}
		boolean escribe = !clave.startsWith("GET ");
		boolean publica = PUBLICAS.contains(clave);
		boolean mfa = clave.contains(" /api/auth/admin/mfa/");

		Map<Integer, Set<String>> errores = new TreeMap<>();
		ruta.errores().forEach(e -> agregar(errores, Integer.parseInt(e.substring(0, 3)), e.substring(4)));
		if (operacion.getRequestBody() != null) {
			agregar(errores, 400, "VALIDACION");
		}
		if (operacion.getRequestBody() != null || (operacion.getParameters() != null && !operacion.getParameters().isEmpty())) {
			agregar(errores, 400, "SOLICITUD_INVALIDA");
		}
		if (escribe) {
			agregar(errores, 403, "CSRF_INVALIDO");
		}
		if (!publica) {
			agregar(errores, 401, "NO_AUTENTICADO");
			agregar(errores, 503, "SERVICIO_NO_DISPONIBLE");
			if (!CUALQUIER_SESION.contains(clave)) {
				agregar(errores, 403, "ACCESO_DENEGADO");
			}
		}

		ApiResponses respuestas = new ApiResponses();
		ApiResponse exito = exito(operacion, ruta);
		respuestas.addApiResponse(String.valueOf(ruta.exito()), exito);
		errores.forEach((estado, codigos) -> respuestas.addApiResponse(String.valueOf(estado),
				respuestaDeError(estado, codigos)));
		operacion.setResponses(respuestas);

		operacion.setSecurity(seguridad(publica, escribe));
		if (DESCRIPCIONES.containsKey(clave)) {
			operacion.setDescription(DESCRIPCIONES.get(clave));
		} else if (mfa) {
			operacion.setDescription("Solo con el token de MFA pendiente (un ADMIN con sesion completa recibe 403).");
		} else if (clave.startsWith("GET /api/auth/me")) {
			operacion.setDescription("Identidad de la sesion actual; vale tambien con MFA pendiente.");
		}
	}

	private static void agregar(Map<Integer, Set<String>> errores, int estado, String codigo) {
		errores.computeIfAbsent(estado, e -> new LinkedHashSet<>()).add(codigo);
	}

	/** Reutiliza el cuerpo que springdoc infirio del tipo de retorno y solo corrige el codigo de exito. */
	private static ApiResponse exito(Operation operacion, Ruta ruta) {
		ApiResponse inferida = operacion.getResponses() == null ? null : operacion.getResponses().get("200");
		if (inferida == null && operacion.getResponses() != null) {
			inferida = operacion.getResponses().values().stream().findFirst().orElse(null);
		}
		ApiResponse exito = new ApiResponse();
		switch (ruta.exito()) {
			case 204 -> exito.description("Sin contenido");
			case 201 -> {
				exito.description("Creado");
				if (inferida != null) {
					exito.content(inferida.getContent());
				}
				if (ruta.location()) {
					exito.addHeaderObject("Location", new Header().description("Ruta del recurso creado")
							.schema(new Schema<String>().types(Set.of("string"))));
				}
			}
			default -> {
				exito.description("OK");
				if (inferida != null) {
					exito.content(inferida.getContent());
				}
			}
		}
		return exito;
	}

	private static ApiResponse respuestaDeError(int estado, Set<String> codigos) {
		Schema<?> modelo = new Schema<>().$ref("#/components/schemas/" + ESQUEMA_ERROR);
		return new ApiResponse().description("Error " + estado + ". codigo: " + String.join(" | ", codigos))
				.content(new Content().addMediaType("application/json", new MediaType().schema(modelo)));
	}

	private static List<SecurityRequirement> seguridad(boolean publica, boolean escribe) {
		List<SecurityRequirement> requisitos = new ArrayList<>();
		SecurityRequirement requisito = new SecurityRequirement();
		if (!publica) {
			requisito.addList(ESQUEMA_SESION);
		}
		if (escribe) {
			requisito.addList(ESQUEMA_CSRF);
		}
		// Una lista vacia es explicita en OpenAPI: sin requisitos de seguridad (ruta publica de lectura).
		if (!requisito.isEmpty()) {
			requisitos.add(requisito);
		}
		return requisitos;
	}
}
