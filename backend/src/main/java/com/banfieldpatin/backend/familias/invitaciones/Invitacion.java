package com.banfieldpatin.backend.familias.invitaciones;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Mapeo de gestion_patin.invitacion (V2). Sin asociaciones: las FK son UUID planos. Nunca se serializa.
 * Solo se guarda el hash del token; el token en claro no existe en esta entidad.
 */
@Entity
@Table(name = "invitacion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invitacion {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "escuela_id", nullable = false, updatable = false)
	private UUID escuelaId;

	@Column(name = "familia_id", nullable = false, updatable = false)
	private UUID familiaId;

	/** Punto de extension de RF-04: ninguna API lo completa todavia. */
	@Column(name = "deportista_id", updatable = false)
	private UUID deportistaId;

	@Column(name = "token_hash", nullable = false, updatable = false, length = 64)
	private String tokenHash;

	@Column(name = "email_sugerido", length = 180)
	private String emailSugerido;

	@Column(name = "expira_en", nullable = false, updatable = false)
	private Instant expiraEn;

	@Column(name = "creada_por", nullable = false, updatable = false)
	private UUID creadaPor;

	@Column(name = "usado_en")
	private Instant usadoEn;

	@Column(name = "usuario_id")
	private UUID usuarioId;

	@Column(name = "revocada_en")
	private Instant revocadaEn;

	@Column(name = "revocada_por")
	private UUID revocadaPor;

	/** Lo fija Java (mismo reloj que expira_en) para que el CHECK expira_en > creado_en se cumpla. */
	@Column(name = "creado_en", nullable = false, updatable = false)
	private Instant creadoEn;

	@Column(name = "actualizado_en", insertable = false, updatable = false)
	private Instant actualizadoEn;

	public static Invitacion crear(UUID escuelaId, UUID familiaId, String tokenHash, String emailSugerido,
			Instant creadoEn, Instant expiraEn, UUID creadaPor) {
		Invitacion i = new Invitacion();
		i.escuelaId = escuelaId;
		i.familiaId = familiaId;
		i.tokenHash = tokenHash;
		i.emailSugerido = emailSugerido;
		i.creadoEn = creadoEn;
		i.expiraEn = expiraEn;
		i.creadaPor = creadaPor;
		return i;
	}
}
