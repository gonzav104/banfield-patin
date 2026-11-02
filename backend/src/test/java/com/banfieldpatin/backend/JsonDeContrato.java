package com.banfieldpatin.backend;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * Ayudas para pruebas de CONTRATO sobre el JSON serializado. Recorren el arbol (claves y valores de texto) en lugar de buscar
 * subcadenas en el texto crudo: los UUID aleatorios podrian contener, por azar, ocho digitos seguidos y una busqueda de
 * subcadenas daria falsos positivos.
 */
public final class JsonDeContrato {

	private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private JsonDeContrato() {
	}

	/** Todas las claves de todos los objetos del arbol, sin repetir. */
	public static java.util.Set<String> claves(JsonNode arbol) {
		java.util.Set<String> claves = new TreeSet<>();
		recorrer(arbol, claves, null);
		return claves;
	}

	/** Todos los valores de texto del arbol que NO son un UUID. */
	public static List<String> textosQueNoSonUuid(JsonNode arbol) {
		List<String> textos = new ArrayList<>();
		recorrer(arbol, null, textos);
		return textos.stream().filter(t -> !UUID.matcher(t).matches()).toList();
	}

	/** El DNI completo no aparece ni como clave ({@code dni}) ni dentro de ningun valor de texto que no sea un UUID. */
	public static List<String> filtracionesDeDni(JsonNode arbol, Collection<String> dniCompletos) {
		List<String> problemas = new ArrayList<>();
		if (claves(arbol).contains("dni")) {
			problemas.add("clave dni");
		}
		for (String texto : textosQueNoSonUuid(arbol)) {
			for (String dni : dniCompletos) {
				if (texto.contains(dni)) {
					problemas.add("valor con el DNI completo " + dni.length() + " digitos");
				}
			}
		}
		return problemas;
	}

	private static void recorrer(JsonNode nodo, java.util.Set<String> claves, List<String> textos) {
		if (nodo == null) {
			return;
		}
		if (nodo.isObject()) {
			for (var campo : nodo.properties()) {
				if (claves != null) {
					claves.add(campo.getKey());
				}
				recorrer(campo.getValue(), claves, textos);
			}
		} else if (nodo.isArray()) {
			nodo.forEach(hijo -> recorrer(hijo, claves, textos));
		} else if (nodo.isString() && textos != null) {
			textos.add(nodo.asString());
		}
	}
}
