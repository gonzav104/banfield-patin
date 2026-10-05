package com.banfieldpatin.backend.familias;

import java.util.Objects;
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

/** Mapeo de gestion_patin.familia (V1): sin asociaciones; el estado cambia solo por activar()/desactivar(). */
@Entity
@Table(name = "familia")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Familia {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "escuela_id", nullable = false)
	private UUID escuelaId;

	@Column(name = "nombre_referencia")
	private String nombreReferencia;

	@Column(nullable = false)
	private boolean activa;

	/** Alta de una familia activa (creada en linea al emitir una invitacion). */
	public static Familia crear(UUID escuelaId, String nombreReferencia) {
		Familia f = new Familia();
		f.escuelaId = escuelaId;
		f.nombreReferencia = nombreReferencia;
		f.activa = true;
		return f;
	}

	/** Cambia el nombre; devuelve true solo si el valor realmente cambio. */
	public boolean renombrar(String nuevoNombre) {
		if (Objects.equals(nombreReferencia, nuevoNombre)) {
			return false;
		}
		this.nombreReferencia = nuevoNombre;
		return true;
	}

	/** Devuelve true solo si la familia estaba inactiva (cambio real de estado). */
	public boolean activar() {
		if (activa) {
			return false;
		}
		activa = true;
		return true;
	}

	/** Devuelve true solo si la familia estaba activa (cambio real de estado). */
	public boolean desactivar() {
		if (!activa) {
			return false;
		}
		activa = false;
		return true;
	}
}
