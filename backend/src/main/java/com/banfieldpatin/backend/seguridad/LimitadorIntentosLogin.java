package com.banfieldpatin.backend.seguridad;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador en memoria por clave (ip|email normalizado). Tras N fallos dentro de la ventana la clave queda
 * bloqueada un tiempo. Solo expone un estado booleano: nunca Retry-After ni tiempos restantes (R3).
 * El estado se pierde al reiniciar y la cota de claves es aproximada bajo concurrencia extrema.
 */
public class LimitadorIntentosLogin {

	private record Estado(int fallos, Instant inicioVentana, Instant bloqueadoHasta, Instant ultimoEvento) {

		boolean bloqueado(Instant ahora) {
			return bloqueadoHasta != null && ahora.isBefore(bloqueadoHasta);
		}
	}

	private final int maxIntentos;
	private final Duration ventana;
	private final Duration bloqueo;
	private final int maxClaves;
	private final Clock reloj;
	private final Map<String, Estado> estados = new ConcurrentHashMap<>();

	public LimitadorIntentosLogin(int maxIntentos, Duration ventana, Duration bloqueo, int maxClaves, Clock reloj) {
		this.maxIntentos = maxIntentos;
		this.ventana = ventana;
		this.bloqueo = bloqueo;
		this.maxClaves = maxClaves;
		this.reloj = reloj;
	}

	public LimitadorIntentosLogin(SeguridadPropiedades.Login cfg, Clock reloj) {
		this(cfg.maxIntentos(), cfg.ventana(), cfg.bloqueo(), cfg.maxClaves(), reloj);
	}

	public static String clave(String ip, String email) {
		return (ip == null ? "" : ip) + "|" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
	}

	public boolean estaBloqueado(String clave) {
		Estado e = estados.get(clave);
		return e != null && e.bloqueado(reloj.instant());
	}

	public void registrarFallo(String clave) {
		Instant ahora = reloj.instant();
		if (!estados.containsKey(clave) && estados.size() >= maxClaves) {
			hacerLugar(ahora);
		}
		estados.compute(clave, (k, actual) -> {
			if (actual != null && actual.bloqueado(ahora)) {
				return actual;
			}
			boolean ventanaVigente = actual != null && actual.inicioVentana() != null
					&& ahora.isBefore(actual.inicioVentana().plus(ventana));
			int fallos = (ventanaVigente ? actual.fallos() : 0) + 1;
			Instant inicio = ventanaVigente ? actual.inicioVentana() : ahora;
			if (fallos >= maxIntentos) {
				return new Estado(0, null, ahora.plus(bloqueo), ahora);
			}
			return new Estado(fallos, inicio, null, ahora);
		});
	}

	public void registrarExito(String clave) {
		estados.remove(clave);
	}

	int tamanio() {
		return estados.size();
	}

	/** Purga entradas vencidas; si sigue lleno, expulsa la mas antigua priorizando las no bloqueadas. */
	private void hacerLugar(Instant ahora) {
		estados.entrySet().removeIf(en -> vencido(en.getValue(), ahora));
		if (estados.size() >= maxClaves) {
			estados.entrySet().stream()
					.min(Comparator.<Map.Entry<String, Estado>, Boolean>comparing(en -> en.getValue().bloqueado(ahora))
							.thenComparing(en -> en.getValue().ultimoEvento()))
					.ifPresent(en -> estados.remove(en.getKey()));
		}
	}

	private boolean vencido(Estado e, Instant ahora) {
		if (e.bloqueado(ahora)) {
			return false;
		}
		return e.inicioVentana() == null || !ahora.isBefore(e.inicioVentana().plus(ventana));
	}
}
