package com.banfieldpatin.backend.usuarios;

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

/** Mapeo de gestion_patin.usuario (V1). Sin asociaciones: las FK son UUID planos. Nunca se serializa. */
@Entity
@Table(name = "usuario")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Usuario {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "escuela_id", nullable = false, updatable = false)
	private UUID escuelaId;

	@Column(name = "familia_id", updatable = false)
	private UUID familiaId;

	@Column(nullable = false)
	private String nombre;

	@Column(nullable = false)
	private String apellido;

	@Column(nullable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Rol rol;

	@Column(name = "email_verificado", nullable = false)
	private boolean emailVerificado;

	@Column(name = "mfa_habilitado", nullable = false)
	private boolean mfaHabilitado;

	@Column(nullable = false)
	private boolean activo;

	@Column(name = "ultimo_acceso_en")
	private Instant ultimoAccesoEn;

	@Column(name = "creado_en", insertable = false, updatable = false)
	private Instant creadoEn;

	@Column(name = "actualizado_en", insertable = false, updatable = false)
	private Instant actualizadoEn;

	/** Alta de usuario activo, sin email verificado ni MFA (reutilizada por bootstrap y registro). */
	public static Usuario crear(UUID escuelaId, UUID familiaId, String nombre, String apellido, String email,
			String passwordHash, Rol rol) {
		Usuario u = new Usuario();
		u.escuelaId = escuelaId;
		u.familiaId = familiaId;
		u.nombre = nombre;
		u.apellido = apellido;
		u.email = email;
		u.passwordHash = passwordHash;
		u.rol = rol;
		u.activo = true;
		return u;
	}

	public void registrarAcceso(Instant ahora) {
		this.ultimoAccesoEn = ahora;
	}

	public void cambiarPasswordHash(String nuevoHash) {
		this.passwordHash = nuevoHash;
	}
}
