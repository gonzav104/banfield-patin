package com.banfieldpatin.backend.seguridad;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * Validacion ESTRUCTURAL de un documento OpenAPI sin ninguna dependencia nueva (no se aprobo un validador completo, p. ej.
 * swagger-parser). RNF-14 pide que la especificacion "valide sin errores": esto comprueba las reglas del estandar que un
 * generador puede romper de verdad, NO el esquema JSON oficial completo:
 * <ul>
 * <li>{@code openapi} es 3.0.x o 3.1.x e {@code info} lleva titulo y version;</li>
 * <li>{@code paths} no esta vacio, cada ruta empieza con {@code /} y cada operacion tiene {@code responses} no vacio, con
 * claves HTTP validas y {@code description} (obligatoria) en cada respuesta;</li>
 * <li>cada parametro de la plantilla {@code {x}} esta declarado como parametro de ruta obligatorio, y viceversa;</li>
 * <li>los {@code operationId} no se repiten;</li>
 * <li>TODO {@code $ref} del documento apunta a un nodo que existe (puntero JSON);</li>
 * <li>cada requisito de seguridad nombra un esquema declarado; {@code requestBody} lleva {@code content};</li>
 * <li>cada nombre de {@code required} de un esquema existe en sus {@code properties}.</li>
 * </ul>
 * Limites: no valida contra el meta-esquema oficial de OpenAPI 3.1 (tipos de cada campo, formatos, palabras reservadas de
 * JSON Schema 2020-12 ni propiedades desconocidas), ni la coherencia semantica de los ejemplos (no hay ninguno).
 */
public final class ValidadorOpenApi {

	private static final Set<String> METODOS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
	private static final Pattern VERSION = Pattern.compile("^3\\.[01]\\.\\d+$");
	private static final Pattern ESTADO = Pattern.compile("^([1-5]\\d\\d|[1-5]XX|default)$");
	private static final Pattern PARAMETRO_DE_RUTA = Pattern.compile("\\{([^}/]+)}");

	private ValidadorOpenApi() {
	}

	/** Lista de problemas encontrados; vacia si el documento pasa todas las reglas. */
	public static List<String> problemas(JsonNode documento) {
		List<String> problemas = new ArrayList<>();
		if (!documento.path("openapi").isString() || !VERSION.matcher(documento.path("openapi").asString()).matches()) {
			problemas.add("'openapi' ausente o con una version distinta de 3.0.x/3.1.x");
		}
		if (documento.path("info").path("title").asString("").isBlank()
				|| documento.path("info").path("version").asString("").isBlank()) {
			problemas.add("'info.title' e 'info.version' son obligatorios");
		}
		JsonNode rutas = documento.path("paths");
		if (!rutas.isObject() || rutas.isEmpty()) {
			problemas.add("'paths' ausente o vacio");
		} else {
			Set<String> operationIds = new HashSet<>();
			for (Map.Entry<String, JsonNode> ruta : rutas.properties()) {
				validarRuta(documento, ruta.getKey(), ruta.getValue(), operationIds, problemas);
			}
		}
		validarReferencias(documento, documento, "#", problemas);
		validarEsquemasRequeridos(documento.path("components").path("schemas"), problemas);
		validarSeguridad(documento.path("security"), documento, "raiz", problemas);
		return problemas;
	}

	private static void validarRuta(JsonNode documento, String ruta, JsonNode item, Set<String> operationIds,
			List<String> problemas) {
		if (!ruta.startsWith("/")) {
			problemas.add("la ruta '" + ruta + "' no empieza con '/'");
		}
		Set<String> enPlantilla = new HashSet<>();
		Matcher m = PARAMETRO_DE_RUTA.matcher(ruta);
		while (m.find()) {
			enPlantilla.add(m.group(1));
		}
		for (Map.Entry<String, JsonNode> campo : item.properties()) {
			if (!METODOS.contains(campo.getKey())) {
				continue;
			}
			String operacion = campo.getKey().toUpperCase() + " " + ruta;
			JsonNode op = campo.getValue();
			JsonNode respuestas = op.path("responses");
			if (!respuestas.isObject() || respuestas.isEmpty()) {
				problemas.add(operacion + ": 'responses' ausente o vacio");
			} else {
				for (Map.Entry<String, JsonNode> r : respuestas.properties()) {
					if (!ESTADO.matcher(r.getKey()).matches()) {
						problemas.add(operacion + ": clave de respuesta invalida '" + r.getKey() + "'");
					}
					// Una respuesta puede ser un $ref a components/responses; ahi la description se valida aparte.
					if (!r.getValue().has("$ref") && r.getValue().path("description").asString("").isBlank()) {
						problemas.add(operacion + ": la respuesta " + r.getKey() + " no tiene 'description'");
					}
				}
			}
			JsonNode id = op.path("operationId");
			if (id.isString() && !operationIds.add(id.asString())) {
				problemas.add(operacion + ": operationId repetido '" + id.asString() + "'");
			}
			if (op.has("requestBody") && !op.path("requestBody").has("$ref")
					&& (!op.path("requestBody").path("content").isObject()
							|| op.path("requestBody").path("content").isEmpty())) {
				problemas.add(operacion + ": 'requestBody.content' ausente o vacio");
			}
			Set<String> declarados = new HashSet<>();
			for (JsonNode parametro : concat(item.path("parameters"), op.path("parameters"))) {
				if ("path".equals(parametro.path("in").asString())) {
					declarados.add(parametro.path("name").asString());
					if (!parametro.path("required").asBoolean(false)) {
						problemas.add(operacion + ": el parametro de ruta '" + parametro.path("name").asString()
								+ "' debe ser required=true");
					}
				}
			}
			if (!declarados.equals(enPlantilla)) {
				problemas.add(operacion + ": parametros de ruta declarados " + declarados + " != plantilla " + enPlantilla);
			}
			validarSeguridad(op.path("security"), documento, operacion, problemas);
		}
	}

	private static List<JsonNode> concat(JsonNode a, JsonNode b) {
		List<JsonNode> todos = new ArrayList<>();
		a.forEach(todos::add);
		b.forEach(todos::add);
		return todos;
	}

	private static void validarSeguridad(JsonNode requisitos, JsonNode documento, String donde, List<String> problemas) {
		for (JsonNode requisito : requisitos) {
			for (String esquema : requisito.propertyNames()) {
				if (!documento.path("components").path("securitySchemes").has(esquema)) {
					problemas.add(donde + ": requisito de seguridad '" + esquema + "' no esta declarado");
				}
			}
		}
	}

	/** Cada {@code $ref} del documento debe resolver (solo referencias internas, que son las que genera springdoc). */
	private static void validarReferencias(JsonNode raiz, JsonNode nodo, String donde, List<String> problemas) {
		if (nodo.isObject()) {
			for (Map.Entry<String, JsonNode> campo : nodo.properties()) {
				if ("$ref".equals(campo.getKey()) && campo.getValue().isString()) {
					String ref = campo.getValue().asString();
					if (!ref.startsWith("#/") || resolver(raiz, ref).isMissingNode()) {
						problemas.add(donde + ": $ref que no resuelve '" + ref + "'");
					}
				} else {
					validarReferencias(raiz, campo.getValue(), donde + "/" + campo.getKey(), problemas);
				}
			}
		} else if (nodo.isArray()) {
			int i = 0;
			for (Iterator<JsonNode> it = nodo.iterator(); it.hasNext(); i++) {
				validarReferencias(raiz, it.next(), donde + "/" + i, problemas);
			}
		}
	}

	private static JsonNode resolver(JsonNode raiz, String ref) {
		JsonNode actual = raiz;
		for (String parte : ref.substring(2).split("/")) {
			actual = actual.path(parte.replace("~1", "/").replace("~0", "~"));
		}
		return actual;
	}

	private static void validarEsquemasRequeridos(JsonNode esquemas, List<String> problemas) {
		for (Map.Entry<String, JsonNode> esquema : esquemas.properties()) {
			JsonNode propiedades = esquema.getValue().path("properties");
			for (JsonNode requerido : esquema.getValue().path("required")) {
				if (!propiedades.has(requerido.asString())) {
					problemas.add("esquema " + esquema.getKey() + ": 'required' nombra '" + requerido.asString()
							+ "', que no esta en 'properties'");
				}
			}
		}
	}
}
