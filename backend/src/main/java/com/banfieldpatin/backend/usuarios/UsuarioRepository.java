package com.banfieldpatin.backend.usuarios;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

	/** El email llega normalizado; lower() coincide con el indice unico uq_usuario_email_escuela. */
	@Query("select u from Usuario u where u.escuelaId = :escuelaId and lower(u.email) = lower(:email)")
	Optional<Usuario> buscarPorEmail(@Param("escuelaId") UUID escuelaId, @Param("email") String email);

	@Query("select count(u) > 0 from Usuario u where u.escuelaId = :escuelaId and lower(u.email) = lower(:email)")
	boolean existeEmail(@Param("escuelaId") UUID escuelaId, @Param("email") String email);

	@Query("select count(u) from Usuario u where u.escuelaId = :escuelaId and u.rol = com.banfieldpatin.backend.usuarios.Rol.ADMIN and u.activo = true")
	long contarAdminsActivos(@Param("escuelaId") UUID escuelaId);

	Optional<Usuario> findByIdAndEscuelaId(UUID id, UUID escuelaId);

	/**
	 * Versión de sesión (V19) de una cuenta ACTIVA, con la fila tomada FOR SHARE hasta el fin de la transacción del login:
	 * una desactivación concurrente (UPDATE de la fila) espera a que el login termine, así que la versión que lleva el token
	 * es la de una cuenta que seguía activa; si la desactivación ganó, no hay fila y el login se rechaza. Vacío = inactiva.
	 */
	@Query(value = "SELECT version_sesion FROM gestion_patin.usuario WHERE id = :id AND activo FOR SHARE", nativeQuery = true)
	Optional<Long> bloquearVersionSesionSiActiva(@Param("id") UUID id);
}
