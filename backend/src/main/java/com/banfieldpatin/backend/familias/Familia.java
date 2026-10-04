package com.banfieldpatin.backend.familias;

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

/** Mapeo minimo de gestion_patin.familia (V1): verificar que este activa, listarla y crearla en linea. */
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
}
