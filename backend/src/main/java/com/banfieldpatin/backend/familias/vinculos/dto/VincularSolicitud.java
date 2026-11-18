package com.banfieldpatin.backend.familias.vinculos.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lote de deportistas a vincular a la familia de la ruta, cada uno con la revision de sus vinculos que se observo.
 * Maximo 50 elementos (se cuentan ANTES de quitar repetidos) y ningun elemento nulo. Un deportista repetido con la misma
 * revision se acepta (el servicio conserva la primera posicion); repetido con revisiones distintas la solicitud es ambigua
 * y se rechaza con 400 SOLICITUD_INVALIDA sin eco de valores. El estado, el caracter principal y quien autoriza los decide
 * el servidor: cualquier otra propiedad (incluida la anterior {@code deportistaIds}) se rechaza con 400 SOLICITUD_INVALIDA.
 */
public record VincularSolicitud(@NotNull @Size(min = 1, max = 50) List<@NotNull @Valid DeportistaAVincular> deportistas) {

	public VincularSolicitud {
		// Copia defensiva que tolera elementos nulos: Bean Validation los informa como 400 VALIDACION.
		deportistas = deportistas == null ? null : Collections.unmodifiableList(new ArrayList<>(deportistas));
		if (deportistas != null) {
			exigirRevisionesCoherentes(deportistas);
		}
	}

	private static void exigirRevisionesCoherentes(List<DeportistaAVincular> deportistas) {
		Map<UUID, Long> revisiones = new HashMap<>();
		for (DeportistaAVincular d : deportistas) {
			if (d == null || d.deportistaId() == null || d.versionEsperada() == null) {
				continue;
			}
			Long previa = revisiones.putIfAbsent(d.deportistaId(), d.versionEsperada());
			if (previa != null && !Objects.equals(previa, d.versionEsperada())) {
				throw new IllegalArgumentException("Revisiones distintas para un mismo deportista");
			}
		}
	}

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}
}
