package com.banfieldpatin.backend.familias.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Solo cadenas decimales no negativas dentro de BIGINT. No cambia la coercion global de Jackson. */
public class VersionFamiliaDecimal extends ValueDeserializer<Long> {
	@Override
	public Long deserialize(JsonParser parser, DeserializationContext contexto) {
		if (parser.currentToken() == JsonToken.VALUE_STRING) {
			String valor = parser.getString();
			if (valor.matches("[0-9]{1,19}")) {
				try {
					return Long.parseLong(valor);
				} catch (NumberFormatException ignorada) {
					// Fuera del rango de BIGINT: mismo error saneado, sin eco del valor.
				}
			}
		}
		return (Long) contexto.handleUnexpectedToken(Long.class, parser);
	}
}
