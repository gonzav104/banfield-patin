package com.banfieldpatin.backend;

import java.util.UUID;

import org.springframework.test.util.ReflectionTestUtils;

import com.banfieldpatin.backend.escuelas.Escuela;
import com.banfieldpatin.backend.familias.Familia;
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

	public static Usuario usuario(UUID id, UUID escuelaId, UUID familiaId, Rol rol, String email, String hash,
			boolean activo) {
		Usuario u = Usuario.crear(escuelaId, familiaId, "Ana", "Perez", email, hash, rol);
		ReflectionTestUtils.setField(u, "id", id);
		ReflectionTestUtils.setField(u, "activo", activo);
		return u;
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
