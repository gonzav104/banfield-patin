package com.banfieldpatin.backend.usuarios.mfa;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Mapeo de gestion_patin.usuario_mfa (V3). El secreto TOTP solo existe cifrado (AES-256-GCM, ver CifradorSecretoMfa).
 * Las altas y los cambios de estado se hacen con consultas condicionales atomicas del repositorio, no con save().
 * Nunca se serializa ni se imprime (sin toString).
 */
@Entity
@Table(name = "usuario_mfa")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsuarioMfa {

	@Id
	@Column(name = "usuario_id")
	private UUID usuarioId;

	@Column(name = "escuela_id", nullable = false, updatable = false)
	private UUID escuelaId;

	@Column(name = "secreto_cifrado", nullable = false)
	private byte[] secretoCifrado;

	@Column(name = "confirmado_en")
	private Instant confirmadoEn;

	@Column(name = "ultimo_paso_usado")
	private Long ultimoPasoUsado;

	@Column(name = "creado_en", insertable = false, updatable = false)
	private Instant creadoEn;

	@Column(name = "actualizado_en", insertable = false, updatable = false)
	private Instant actualizadoEn;

	public boolean estaConfirmado() {
		return confirmadoEn != null;
	}
}
