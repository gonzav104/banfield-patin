package com.banfieldpatin.backend;

import java.time.Instant;
import java.util.UUID;

import org.springframework.test.util.ReflectionTestUtils;

import com.banfieldpatin.backend.deportistas.Deportista;
import com.banfieldpatin.backend.escuelas.Escuela;
import com.banfieldpatin.backend.familias.Familia;
import com.banfieldpatin.backend.familias.invitaciones.Invitacion;
import com.banfieldpatin.backend.familias.tutores.Tutor;
import com.banfieldpatin.backend.familias.vinculos.EstadoVinculo;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportista;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.Usuario;

/** Construye entidades sin base de datos (los ids los asigna la BD en produccion). */
public final class FixturesDominio {

	private FixturesDominio() {
	}

	public static Escuela escuela(UUID id, boolean activa) {
		Escuela e = instancia(Escuela.class);
		ReflectionTestUtils.setField(e, "id", id);
		ReflectionTestUtils.setField(e, "slug", "escuela-test");
		ReflectionTestUtils.setField(e, "nombre", "Escuela de prueba");
		ReflectionTestUtils.setField(e, "activa", activa);
		ReflectionTestUtils.setField(e, "maxAdministradores", (short) 2);
		return e;
	}

	public static Familia familia(UUID id, UUID escuelaId, boolean activa) {
		Familia f = instancia(Familia.class);
		ReflectionTestUtils.setField(f, "id", id);
		ReflectionTestUtils.setField(f, "escuelaId", escuelaId);
		ReflectionTestUtils.setField(f, "nombreReferencia", "Familia Prueba");
		ReflectionTestUtils.setField(f, "activa", activa);
		return f;
	}

	/** Tutor activo sin DNI ni otros datos opcionales, con id asignado. */
	public static Tutor tutor(UUID id, UUID escuelaId, UUID familiaId, String nombre, String apellido) {
		Tutor t = Tutor.crear(escuelaId, familiaId, nombre, apellido, null, null, null, null);
		ReflectionTestUtils.setField(t, "id", id);
		return t;
	}

	/** Deportista con id asignado y solo los datos obligatorios (sin CUIL ni opcionales); {@code activo} se fuerza. */
	public static Deportista deportista(UUID id, UUID escuelaId, String dni, String nombre, String apellido,
			boolean activo) {
		Deportista d = Deportista.crear(escuelaId, nombre, apellido, dni, null, null, null, null, null, null, null, null,
				null, null);
		ReflectionTestUtils.setField(d, "id", id);
		ReflectionTestUtils.setField(d, "activo", activo);
		return d;
	}

	/** Vinculo con id, estado y principal fijados (siempre con una autorizacion de ejemplo). */
	public static FamiliaDeportista vinculo(UUID id, UUID escuelaId, UUID familiaId, UUID deportistaId,
			EstadoVinculo estado, boolean esPrincipal) {
		FamiliaDeportista v = FamiliaDeportista.activo(escuelaId, familiaId, deportistaId, UUID.randomUUID(),
				Instant.parse("2026-01-01T10:00:00Z"), esPrincipal);
		ReflectionTestUtils.setField(v, "id", id);
		ReflectionTestUtils.setField(v, "estado", estado);
		return v;
	}

	public static Usuario usuario(UUID id, UUID escuelaId, UUID familiaId, Rol rol, String email, String hash,
			boolean activo) {
		Usuario u = Usuario.crear(escuelaId, familiaId, "Ana", "Perez", email, hash, rol);
		ReflectionTestUtils.setField(u, "id", id);
		ReflectionTestUtils.setField(u, "activo", activo);
		return u;
	}

	/** Invitacion pendiente creada en {@code creadoEn} que vence en {@code expiraEn}. */
	public static Invitacion invitacion(UUID id, UUID escuelaId, UUID familiaId, UUID creadaPor, Instant creadoEn,
			Instant expiraEn) {
		Invitacion i = Invitacion.crear(escuelaId, familiaId, "a".repeat(64), null, creadoEn, expiraEn, creadaPor);
		ReflectionTestUtils.setField(i, "id", id);
		return i;
	}

	private static <T> T instancia(Class<T> tipo) {
		try {
			var ctor = tipo.getDeclaredConstructor();
			ctor.setAccessible(true);
			return ctor.newInstance();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
