package com.banfieldpatin.backend.familias.tutores;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Mapeo de gestion_patin.tutor (V1, V21, V22): sin asociaciones (UUID planos). La escuela y la familia son inmutables
 * ({@code updatable = false}); la cuenta ({@code usuario_id}), el origen y quién declaró se escriben solo al crear o por
 * UPDATE condicional, nunca por Hibernate; {@code activo} nace en true y cambia solo mediante los comandos de estado. Los datos personales se cambian
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

	/** Cuenta asociada (V21): se escribe al crear el perfil propio y por el reclamo (UPDATE condicional); nunca por Hibernate. */
	@Column(name = "usuario_id", updatable = false)
	private UUID usuarioId;

	/** Quién cargó el dato: ADMIN (lo existente) o FAMILIA (perfil propio). */
	@Column(nullable = false, updatable = false)
	private String origen = "ADMIN";

	/**
	 * DECLARADO / VERIFICADO / OBSERVADO (estado de los DATOS, no de la cuenta). Cambia solo por {@link #verificar},
	 * {@link #observar} y {@link #actualizarPorFamilia} (un OBSERVADO corregido vuelve a DECLARADO).
	 */
	@Column(nullable = false)
	private String verificacion = "VERIFICADO";

	/** Quién y cuándo resolvió el dato declarado (V22): nulos mientras está DECLARADO o si lo cargó el club. */
	@Column(name = "verificado_por")
	private UUID verificadoPor;

	@Column(name = "verificado_en")
	private Instant verificadoEn;

	/** Lo que el club pide corregir; existe si y solo si está OBSERVADO. Se muestra a la familia; nunca se audita. */
	@Column(name = "motivo_observacion")
	private String motivoObservacion;

	/** Alta familiar repetible (V22): clave de la cuenta que declaró y huella de los datos enviados. */
	@Column(updatable = false)
	private UUID idempotencia;

	@Column(name = "huella_alta", updatable = false)
	private String huellaAlta;

	@Column(name = "creado_en", insertable = false, updatable = false)
	private Instant creadoEn;

	/** DECLARADO (la propia persona) o VERIFICADO (ADMIN invitó a ese tutor); nulo sin cuenta. */
	@Column(name = "vinculo_cuenta", updatable = false)
	private String vinculoCuenta;

	@Column(name = "declarado_por", updatable = false)
	private UUID declaradoPor;

	@Column(nullable = false)
	private boolean activo;

	/** Revision de escritura (FE-05/U4): avanza cuando cambian datos o el estado del tutor; el predicado del UPDATE lo hace atomico. */
	@Version
	@Column(nullable = false)
	private long version;

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

	/**
	 * Perfil propio de una cuenta FAMILIA (U21-BIS C1): origen FAMILIA, datos DECLARADOS y cuenta vinculada por declaración.
	 * No concede ningún permiso ni acredita identidad ni representación legal.
	 */
	public static Tutor crearPerfilPropio(UUID escuelaId, UUID familiaId, UUID usuarioId, String nombre, String apellido,
			String dni, String telefono, String email, String parentesco) {
		Tutor t = crear(escuelaId, familiaId, nombre, apellido, dni, telefono, email, parentesco);
		t.usuarioId = usuarioId;
		t.origen = "FAMILIA";
		t.verificacion = "DECLARADO";
		t.vinculoCuenta = "DECLARADO";
		t.declaradoPor = usuarioId;
		return t;
	}

	/**
	 * Otro adulto declarado por la familia (U21-BIS C2), sin cuenta: origen FAMILIA, datos DECLARADOS. Registra quién lo
	 * declaró y la clave del alta. No concede acceso a deportistas ni responsabilidades.
	 */
	public static Tutor declararPorFamilia(UUID escuelaId, UUID familiaId, UUID declarante, String nombre, String apellido,
			String dni, String telefono, String email, String parentesco, UUID idempotencia, String huellaAlta) {
		Tutor t = crear(escuelaId, familiaId, nombre, apellido, dni, telefono, email, parentesco);
		t.origen = "FAMILIA";
		t.verificacion = "DECLARADO";
		t.declaradoPor = declarante;
		t.idempotencia = idempotencia;
		t.huellaAlta = huellaAlta;
		return t;
	}

	/** El club confirmó que la cuenta asociada ES esta persona (invitación dirigida de ADMIN); un reclamo «soy yo» no lo logra. */
	public boolean cuentaConfirmada() {
		return "VERIFICADO".equals(vinculoCuenta);
	}

	/** La identidad (nombre, apellido, DNI) ya fue verificada por ADMIN: la familia no la cambia sola. */
	public boolean identidadVerificada() {
		return "VERIFICADO".equals(verificacion);
	}

	public boolean observado() {
		return "OBSERVADO".equals(verificacion);
	}

	/** ADMIN verifica los datos tal como están (la revisión observada la exige el servicio). */
	public void verificar(UUID admin, Instant instante) {
		verificacion = "VERIFICADO";
		verificadoPor = admin;
		verificadoEn = instante;
		motivoObservacion = null;
	}

	/** ADMIN observa los datos con un motivo para la familia. */
	public void observar(UUID admin, Instant instante, String motivo) {
		verificacion = "OBSERVADO";
		verificadoPor = admin;
		verificadoEn = instante;
		motivoObservacion = motivo;
	}

	/**
	 * Cambio hecho por la familia: como {@link #actualizar}, y si el tutor estaba OBSERVADO y algo cambió vuelve a DECLARADO
	 * (sin resolución ni motivo) para que el club lo revise de nuevo. Un VERIFICADO sigue VERIFICADO: la familia solo puede
	 * haber cambiado su contacto (el servicio rechaza antes un cambio de identidad respaldada).
	 */
	public List<String> actualizarPorFamilia(String nombre, String apellido, String dni, String telefono, String email,
			String parentesco) {
		List<String> cambios = actualizar(nombre, apellido, dni, telefono, email, parentesco);
		if (!cambios.isEmpty() && observado()) {
			verificacion = "DECLARADO";
			verificadoPor = null;
			verificadoEn = null;
			motivoObservacion = null;
		}
		return cambios;
	}

	void activar() {
		activo = true;
	}

	void desactivar() {
		activo = false;
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
