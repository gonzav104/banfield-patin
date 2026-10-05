package com.banfieldpatin.backend.familias.vinculos;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Mapeo de gestion_patin.familia_deportista (V1 + V4): sin asociaciones (UUID planos). Maquina de estados:
 * sin fila -> {@link #activo} (ACTIVO); ACTIVO -> {@link #revocar} (REVOCADO, sin principal); cualquier fila no ACTIVA
 * se reutiliza con {@link #reactivar} (la fila no se duplica: uq_familia_deportista). {@code es_principal} se escribe
 * SIEMPRE de forma explicita (el factory y reactivar reciben un booleano obligatorio): nunca se usa el valor por defecto
 * {@code true} de V1, y por eso no hay {@code @DynamicInsert} ni {@code insertable = false}. {@code creado_en} lo fija la
 * base y no se mapea, de modo que sobrevive a las reactivaciones.
 */
@Entity
@Table(name = "familia_deportista")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FamiliaDeportista {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "escuela_id", nullable = false, updatable = false)
	private UUID escuelaId;

	@Column(name = "familia_id", nullable = false, updatable = false)
	private UUID familiaId;

	@Column(name = "deportista_id", nullable = false, updatable = false)
	private UUID deportistaId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private EstadoVinculo estado;

	@Column(name = "es_principal", nullable = false)
	private boolean esPrincipal;

	@Column(name = "autorizado_por")
	private UUID autorizadoPor;

	@Column(name = "autorizado_en")
	private Instant autorizadoEn;

	/** Vinculo nuevo creado por un ADMIN: nace ACTIVO con su autorizacion (exigida por ck_fd_activo_autorizado). */
	public static FamiliaDeportista activo(UUID escuelaId, UUID familiaId, UUID deportistaId, UUID adminId,
			Instant ahora, boolean principal) {
		FamiliaDeportista fd = new FamiliaDeportista();
		fd.escuelaId = escuelaId;
		fd.familiaId = familiaId;
		fd.deportistaId = deportistaId;
		fd.estado = EstadoVinculo.ACTIVO;
		fd.esPrincipal = principal;
		fd.autorizadoPor = adminId;
		fd.autorizadoEn = ahora;
		return fd;
	}

	/** Reutiliza una fila REVOCADA, PENDIENTE o RECHAZADA: vuelve a ACTIVO con una autorizacion nueva. */
	public void reactivar(UUID adminId, Instant ahora, boolean principal) {
		if (estado == EstadoVinculo.ACTIVO) {
			throw new IllegalStateException("El vinculo ya esta activo");
		}
		this.estado = EstadoVinculo.ACTIVO;
		this.esPrincipal = principal;
		this.autorizadoPor = adminId;
		this.autorizadoEn = ahora;
	}

	/** ACTIVO -> REVOCADO sin principal; devuelve false (sin cambio) si el vinculo no estaba ACTIVO. */
	public boolean revocar() {
		if (estado != EstadoVinculo.ACTIVO) {
			return false;
		}
		this.estado = EstadoVinculo.REVOCADO;
		this.esPrincipal = false;
		return true;
	}

	/** Solo un vinculo ACTIVO puede ser principal; devuelve false si ya lo era. */
	public boolean marcarPrincipal() {
		if (estado != EstadoVinculo.ACTIVO) {
			throw new IllegalStateException("Solo un vinculo ACTIVO puede ser principal");
		}
		if (esPrincipal) {
			return false;
		}
		this.esPrincipal = true;
		return true;
	}

	/** Devuelve false si el vinculo no era principal. */
	public boolean quitarPrincipal() {
		if (!esPrincipal) {
			return false;
		}
		this.esPrincipal = false;
		return true;
	}
}
