package com.banfieldpatin.backend.familias.responsables;

import java.time.Instant;
import java.util.UUID;

/** Proyección ADMIN explícita, sin DNI, contactos, cuentas ni datos de idempotencia. */
public record VinculoTutorRespuesta(UUID id, UUID familiaId, UUID tutorId, String nombreTutor, String apellidoTutor,
		TipoVinculoTutor tipo, Instant creadoEn, Instant cerradoEn, CausaCierreVigencia causaCierre, String version) {

	public VinculoTutorRespuesta(UUID id, UUID familiaId, UUID tutorId, String nombreTutor, String apellidoTutor,
			TipoVinculoTutor tipo, Instant creadoEn, Instant cerradoEn, CausaCierreVigencia causaCierre, long version) {
		this(id, familiaId, tutorId, nombreTutor, apellidoTutor, tipo, creadoEn, cerradoEn, causaCierre,
				Long.toString(version));
	}

	@Override
	public String toString() {
		return "VinculoTutorRespuesta[id=" + id + "]";
	}
}
