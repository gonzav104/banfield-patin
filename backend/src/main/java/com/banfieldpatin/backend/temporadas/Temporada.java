package com.banfieldpatin.backend.temporadas;

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
 * Mapeo de gestion_patin.temporada (V1): sin asociaciones. El anio es la identidad funcional y no se modifica; el
 * estado cambia solo por activar()/desactivar(). Las marcas de tiempo las asigna la base (default y trigger) y no se mapean.
 */
@Entity
@Table(name = "temporada")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Temporada {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "escuela_id", nullable = false)
	private UUID escuelaId;

	@Column(nullable = false)
	private Short anio;

	@Column(nullable = false)
	private boolean activa;

	/** Alta de una temporada INACTIVA (O10): activarla es una accion explicita aparte. */
	public static Temporada crear(UUID escuelaId, int anio) {
		Temporada t = new Temporada();
		t.escuelaId = escuelaId;
		t.anio = (short) anio;
		t.activa = false;
		return t;
	}

	/** Devuelve true solo si estaba inactiva (cambio real de estado). */
	public boolean activar() {
		if (activa) {
			return false;
		}
		activa = true;
		return true;
	}

	/** Devuelve true solo si estaba activa (cambio real de estado). */
	public boolean desactivar() {
		if (!activa) {
			return false;
		}
		activa = false;
		return true;
	}
}
