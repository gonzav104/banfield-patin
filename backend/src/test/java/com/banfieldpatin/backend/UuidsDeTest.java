package com.banfieldpatin.backend;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * UUID DETERMINISTAS para pruebas que afirman que un texto «no contiene» un dato. Un {@code UUID.randomUUID()} impreso en ese texto
 * puede contener por azar el dato prohibido (por ejemplo «007» en un UUID hexadecimal) y la prueba pasa o falla según la corrida.
 * Estos UUID salen de una semilla (el mismo valor en toda corrida y máquina) y se descartan los que contengan algún texto
 * prohibido, sin distinguir mayúsculas: la propiedad se garantiza al construirlos y se verifica en {@code UuidsDeTestTest}.
 */
public final class UuidsDeTest {

	private static final int INTENTOS_MAXIMOS = 10_000;

	private UuidsDeTest() {
	}

	/** El mismo UUID para la misma semilla y los mismos textos prohibidos; ninguno de esos textos aparece en su representación. */
	public static UUID sin(String semilla, String... prohibidos) {
		for (int n = 0; n < INTENTOS_MAXIMOS; n++) {
			UUID candidato = UUID.nameUUIDFromBytes((semilla + "#" + n).getBytes(StandardCharsets.UTF_8));
			String texto = candidato.toString().toLowerCase(Locale.ROOT);
			boolean limpio = true;
			for (String prohibido : prohibidos) {
				if (texto.contains(prohibido.toLowerCase(Locale.ROOT))) {
					limpio = false;
					break;
				}
			}
			if (limpio) {
				return candidato;
			}
		}
		throw new IllegalStateException("no se encontro un UUID sin los textos prohibidos para la semilla " + semilla);
	}
}
