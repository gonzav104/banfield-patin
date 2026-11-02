package com.banfieldpatin.backend.temporadas.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Lectura ESTRICTA del anio de una temporada: solo un entero JSON que entre en un {@code int}. Jackson por defecto convierte en
 * silencio {@code 2027.9} en 2027, {@code "2027"} en 2027 y {@code 1e3} en 1000; para un identificador funcional como el anio eso
 * es un error de datos disfrazado de exito. Cualquier otra cosa (decimal, notacion exponencial, cadena, booleano, arreglo,
 * objeto o un entero fuera de {@code int}) se reporta como cuerpo ilegible: 400 SOLICITUD_INVALIDA, sin eco del valor. Se aplica
 * SOLO a este campo con {@code @JsonDeserialize}: no cambia la configuracion global de Jackson ni otros endpoints. El nulo no
 * llega aqui (Jackson lo resuelve antes) y lo rechaza {@code @NotNull} con 400 VALIDACION; el rango 2020 a 2100 lo valida
 * Bean Validation.
 */
public class AnioEstricto extends ValueDeserializer<Integer> {

	@Override
	public Integer deserialize(JsonParser parser, DeserializationContext contexto) {
		if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT && parser.getNumberType() == JsonParser.NumberType.INT) {
			return parser.getIntValue();
		}
		return (Integer) contexto.handleUnexpectedToken(Integer.class, parser);
	}
}
