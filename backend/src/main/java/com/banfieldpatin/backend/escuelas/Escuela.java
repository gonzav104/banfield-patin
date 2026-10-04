package com.banfieldpatin.backend.escuelas;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Mapeo de solo lectura de gestion_patin.escuela (V1). Las columnas smallint se mapean como Short. */
@Entity
@Table(name = "escuela")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Escuela {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String slug;

	@Column(nullable = false)
	private String nombre;

	@Column(nullable = false)
	private boolean activa;

	@Column(name = "max_administradores", nullable = false)
	private Short maxAdministradores;
}
