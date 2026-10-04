package com.banfieldpatin.backend.seguridad.mfa;

import java.util.Locale;

/**
 * Base32 de RFC 4648 (alfabeto A-Z2-7), sin dependencias. Las aplicaciones autenticadoras esperan este formato
 * para el secreto compartido; el relleno "=" es opcional para ellas, por eso se puede omitir.
 */
public final class Base32 {

	private static final char[] ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

	private Base32() {
	}

	public static String codificar(byte[] datos, boolean conRelleno) {
		StringBuilder salida = new StringBuilder((datos.length + 4) / 5 * 8);
		int buffer = 0;
		int bits = 0;
		for (byte b : datos) {
			buffer = (buffer << 8) | (b & 0xFF);
			bits += 8;
			while (bits >= 5) {
				salida.append(ALFABETO[(buffer >> (bits - 5)) & 0x1F]);
				bits -= 5;
			}
			buffer &= (1 << bits) - 1;
		}
		if (bits > 0) {
			salida.append(ALFABETO[(buffer << (5 - bits)) & 0x1F]);
		}
		if (conRelleno) {
			while (salida.length() % 8 != 0) {
				salida.append('=');
			}
		}
		return salida.toString();
	}

	/** Decodifica ignorando relleno y mayusculas/minusculas; un caracter fuera del alfabeto es un error. */
	public static byte[] decodificar(String texto) {
		String limpio = texto.strip().toUpperCase(Locale.ROOT);
		int fin = limpio.length();
		while (fin > 0 && limpio.charAt(fin - 1) == '=') {
			fin--;
		}
		byte[] salida = new byte[fin * 5 / 8];
		int buffer = 0;
		int bits = 0;
		int indice = 0;
		for (int i = 0; i < fin; i++) {
			int valor = valor(limpio.charAt(i));
			buffer = (buffer << 5) | valor;
			bits += 5;
			if (bits >= 8) {
				salida[indice++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
				bits -= 8;
				buffer &= (1 << bits) - 1;
			}
		}
		return salida;
	}

	private static int valor(char c) {
		if (c >= 'A' && c <= 'Z') {
			return c - 'A';
		}
		if (c >= '2' && c <= '7') {
			return c - '2' + 26;
		}
		throw new IllegalArgumentException("Caracter Base32 invalido");
	}
}
