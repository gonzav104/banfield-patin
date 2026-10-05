package com.banfieldpatin.backend.familias.vinculos.dto;

import java.time.Instant;
import java.util.UUID;

import com.banfieldpatin.backend.deportistas.Deportista;
import com.banfieldpatin.backend.familias.Familia;
import com.banfieldpatin.backend.familias.vinculos.EstadoVinculo;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportista;

/**
 * Vinculo tal como lo ve un ADMIN. Sin escuelaId, sin el id de quien autorizo y sin DNI ni otros datos personales del
 * deportista (solo nombre y apellido). Los listados lo construyen con {@code select new} (una sola consulta con joins).
 */
public record VinculoRespuesta(UUID vinculoId, UUID familiaId, String familiaNombre, UUID deportistaId,
		String deportistaNombre, String deportistaApellido, boolean deportistaActivo, EstadoVinculo estado,
		boolean esPrincipal, Instant autorizadoEn) {

	public static VinculoRespuesta de(FamiliaDeportista fd, Familia familia, Deportista deportista) {
		return new VinculoRespuesta(fd.getId(), familia.getId(), familia.getNombreReferencia(), deportista.getId(),
				deportista.getNombre(), deportista.getApellido(), deportista.isActivo(), fd.getEstado(),
				fd.isEsPrincipal(), fd.getAutorizadoEn());
	}

	/** Los nombres de personas no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "VinculoRespuesta[vinculoId=" + vinculoId + "]";
	}
}
