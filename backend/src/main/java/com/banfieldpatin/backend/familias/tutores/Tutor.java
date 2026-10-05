package com.banfieldpatin.backend.familias.tutores;

import java.util.ArrayList;
import java.util.List;
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

/**
 * Mapeo de gestion_patin.tutor (V1): sin asociaciones (UUID planos). La escuela y la familia son inmutables
 * ({@code updatable = false}); {@code usuario_id} NO se mapea (la vinculacion tutor-usuario no es parte de este
 * modulo); {@code activo} nace en true y no se modifica (los tutores no se desactivan). Los datos personales se cambian
 * solo con {@link #actualizar}, que informa los NOMBRES de los campos modificados (nunca sus valores).
 */
@Entity
@Table(name = "tutor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tutor {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "escuela_id", nullable = false, updatable = false)
	private UUID escuelaId;

	@Column(name = "familia_id", nullable = false, updatable = false)
	private UUID familiaId;

	@Column(nullable = false)
	private String nombre;

	@Column(nullable = false)
	private String apellido;

	private String dni;

	private String telefono;

	private String email;

	private String parentesco;

	@Column(nullable = false, updatable = false)
	private boolean activo;

	/** Alta de un tutor activo de la familia, sin usuario asociado. */
	public static Tutor crear(UUID escuelaId, UUID familiaId, String nombre, String apellido, String dni,
			String telefono, String email, String parentesco) {
		Tutor t = new Tutor();
		t.escuelaId = escuelaId;
		t.familiaId = familiaId;
		t.nombre = nombre;
		t.apellido = apellido;
		t.dni = dni;
		t.telefono = telefono;
		t.email = email;
		t.parentesco = parentesco;
		t.activo = true;
		return t;
	}

	/** Reemplazo completo de los datos personales; devuelve los nombres de los campos cuyo valor cambio. */
	public List<String> actualizar(String nombre, String apellido, String dni, String telefono, String email,
			String parentesco) {
		List<String> cambios = new ArrayList<>();
		if (!Objects.equals(this.nombre, nombre)) {
			this.nombre = nombre;
			cambios.add("nombre");
		}
		if (!Objects.equals(this.apellido, apellido)) {
			this.apellido = apellido;
			cambios.add("apellido");
		}
		if (!Objects.equals(this.dni, dni)) {
			this.dni = dni;
			cambios.add("dni");
		}
		if (!Objects.equals(this.telefono, telefono)) {
			this.telefono = telefono;
			cambios.add("telefono");
		}
		if (!Objects.equals(this.email, email)) {
			this.email = email;
			cambios.add("email");
		}
		if (!Objects.equals(this.parentesco, parentesco)) {
			this.parentesco = parentesco;
			cambios.add("parentesco");
		}
		return List.copyOf(cambios);
	}
}
