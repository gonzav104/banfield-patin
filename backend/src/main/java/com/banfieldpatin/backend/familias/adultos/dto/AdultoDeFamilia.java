package com.banfieldpatin.backend.familias.adultos.dto;

import java.util.UUID;

/**
 * Un adulto ACTIVO de la propia familia tal como lo ve la familia (U21-BIS C2). Nunca lleva el DNI completo, la escuela, la
 * familia, la cuenta ni quién lo verificó. Tres planos separados:
 * <ul>
 * <li>{@code estado}: de los DATOS (DECLARADO, VERIFICADO u OBSERVADO con {@code motivoObservacion} del club);</li>
 * <li>{@code esMiPerfil} / {@code tieneCuentaPropia}: si la persona tiene cuenta (la mía u otra), que no da permisos;</li>
 * <li>{@code editable} / {@code identidadEditable}: qué puede cambiar quien consulta (un adulto con cuenta propia actualiza sus
 * datos él mismo; la identidad verificada o con un DNI aprobado la corrige el club).</li>
 * </ul>
 */
public record AdultoDeFamilia(UUID id, String nombre, String apellido, String dniEnmascarado, String telefono, String email,
		String parentesco, String estado, String motivoObservacion, boolean cargadoPorElClub, boolean esMiPerfil,
		boolean tieneCuentaPropia, boolean editable, boolean identidadEditable, String version) {

	@Override
	public String toString() {
		return "AdultoDeFamilia[id=" + id + "]";
	}
}
