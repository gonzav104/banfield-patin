package com.banfieldpatin.backend.familias;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Mapeo minimo de gestion_patin.familia (V1), suficiente para verificar que la familia este activa. */
@Entity
@Table(name = "familia")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Familia {

	@Id
	private UUID id;

	@Column(name = "escuela_id", nullable = false)
	private UUID escuelaId;

	@Column(name = "nombre_referencia")
	private String nombreReferencia;

	@Column(nullable = false)
	private boolean activa;
}
