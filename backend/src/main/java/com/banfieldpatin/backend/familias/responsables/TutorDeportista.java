package com.banfieldpatin.backend.familias.responsables;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Una vigencia identificable e independiente de los estados de sus registros padre. */
@Entity
@Table(name = "tutor_deportista")
public class TutorDeportista {

	@Id
	private UUID id;

	@Column(nullable = false, updatable = false)
	private UUID escuelaId;

	@Column(nullable = false, updatable = false)
	private UUID familiaId;

	@Column(nullable = false, updatable = false)
	private UUID deportistaId;

	@Column(nullable = false, updatable = false)
	private UUID tutorId;

	@Enumerated(EnumType.STRING)
	@Column(length = 24)
	private TipoVinculoTutor tipo;

	@Version
	@Column(nullable = false)
	private long version;

	@Column(nullable = false, updatable = false)
	private UUID solicitudId;

	@Column(nullable = false, updatable = false, length = 64)
	private String huellaSolicitud;

	@Column(nullable = false, updatable = false)
	private Instant creadoEn;

	@Column(nullable = false, updatable = false)
	private UUID creadoPor;

	private Instant cerradoEn;

	private UUID cerradoPor;

	@Enumerated(EnumType.STRING)
	@Column(length = 40)
	private CausaCierreVigencia causaCierre;

	protected TutorDeportista() {
	}
}
