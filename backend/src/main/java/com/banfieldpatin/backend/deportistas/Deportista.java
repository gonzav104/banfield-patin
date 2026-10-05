package com.banfieldpatin.backend.deportistas;

import java.time.LocalDate;
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
 * Mapeo de los datos PERMANENTES de gestion_patin.deportista (V1): sin datos de temporada, sin asociaciones (UUID
 * planos) y sin setters. La escuela es inmutable ({@code updatable = false}). Los datos personales se cambian solo con
 * {@link #actualizar}, que informa los NOMBRES de los campos modificados (nunca sus valores); el estado, solo con
 * {@link #activar()} y {@link #desactivar()}. Los DNI y CUIL llegan ya normalizados (solo digitos).
 */
@Entity
@Table(name = "deportista")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Deportista {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "escuela_id", nullable = false, updatable = false)
	private UUID escuelaId;

	@Column(nullable = false)
	private String nombre;

	@Column(nullable = false)
	private String apellido;

	@Column(nullable = false)
	private String dni;

	private String cuil;

	@Column(name = "fecha_nacimiento")
	private LocalDate fechaNacimiento;

	private String nacionalidad;

	private String domicilio;

	@Column(name = "otros_datos_domicilio")
	private String otrosDatosDomicilio;

	private String localidad;

	private String partido;

	@Column(name = "codigo_postal")
	private String codigoPostal;

	@Column(name = "telefono_contacto")
	private String telefonoContacto;

	@Column(name = "email_federativo")
	private String emailFederativo;

	@Column(nullable = false)
	private boolean activo;

	/** Alta de un deportista activo de la escuela, sin ningun vinculo con familias. */
	public static Deportista crear(UUID escuelaId, String nombre, String apellido, String dni, String cuil,
			LocalDate fechaNacimiento, String nacionalidad, String domicilio, String otrosDatosDomicilio,
			String localidad, String partido, String codigoPostal, String telefonoContacto, String emailFederativo) {
		Deportista d = new Deportista();
		d.escuelaId = escuelaId;
		d.nombre = nombre;
		d.apellido = apellido;
		d.dni = dni;
		d.cuil = cuil;
		d.fechaNacimiento = fechaNacimiento;
		d.nacionalidad = nacionalidad;
		d.domicilio = domicilio;
		d.otrosDatosDomicilio = otrosDatosDomicilio;
		d.localidad = localidad;
		d.partido = partido;
		d.codigoPostal = codigoPostal;
		d.telefonoContacto = telefonoContacto;
		d.emailFederativo = emailFederativo;
		d.activo = true;
		return d;
	}

	/**
	 * Reemplazo completo de los datos personales (no toca el estado); devuelve los nombres de los campos cuyo valor
	 * cambio, en el orden de la solicitud.
	 */
	public List<String> actualizar(String nombre, String apellido, String dni, String cuil, LocalDate fechaNacimiento,
			String nacionalidad, String domicilio, String otrosDatosDomicilio, String localidad, String partido,
			String codigoPostal, String telefonoContacto, String emailFederativo) {
		List<String> cambios = new ArrayList<>();
		if (!Objects.equals(this.dni, dni)) {
			this.dni = dni;
			cambios.add("dni");
		}
		if (!Objects.equals(this.nombre, nombre)) {
			this.nombre = nombre;
			cambios.add("nombre");
		}
		if (!Objects.equals(this.apellido, apellido)) {
			this.apellido = apellido;
			cambios.add("apellido");
		}
		if (!Objects.equals(this.cuil, cuil)) {
			this.cuil = cuil;
			cambios.add("cuil");
		}
		if (!Objects.equals(this.fechaNacimiento, fechaNacimiento)) {
			this.fechaNacimiento = fechaNacimiento;
			cambios.add("fechaNacimiento");
		}
		if (!Objects.equals(this.nacionalidad, nacionalidad)) {
			this.nacionalidad = nacionalidad;
			cambios.add("nacionalidad");
		}
		if (!Objects.equals(this.domicilio, domicilio)) {
			this.domicilio = domicilio;
			cambios.add("domicilio");
		}
		if (!Objects.equals(this.otrosDatosDomicilio, otrosDatosDomicilio)) {
			this.otrosDatosDomicilio = otrosDatosDomicilio;
			cambios.add("otrosDatosDomicilio");
		}
		if (!Objects.equals(this.localidad, localidad)) {
			this.localidad = localidad;
			cambios.add("localidad");
		}
		if (!Objects.equals(this.partido, partido)) {
			this.partido = partido;
			cambios.add("partido");
		}
		if (!Objects.equals(this.codigoPostal, codigoPostal)) {
			this.codigoPostal = codigoPostal;
			cambios.add("codigoPostal");
		}
		if (!Objects.equals(this.telefonoContacto, telefonoContacto)) {
			this.telefonoContacto = telefonoContacto;
			cambios.add("telefonoContacto");
		}
		if (!Objects.equals(this.emailFederativo, emailFederativo)) {
			this.emailFederativo = emailFederativo;
			cambios.add("emailFederativo");
		}
		return List.copyOf(cambios);
	}

	/** Devuelve true solo si el estado cambio (idempotente). */
	public boolean activar() {
		if (activo) {
			return false;
		}
		activo = true;
		return true;
	}

	/** Devuelve true solo si el estado cambio (idempotente). */
	public boolean desactivar() {
		if (!activo) {
			return false;
		}
		activo = false;
		return true;
	}
}
