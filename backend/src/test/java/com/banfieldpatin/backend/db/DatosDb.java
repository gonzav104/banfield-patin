package com.banfieldpatin.backend.db;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Altas y limpieza con SQL directo (sin pasar por las entidades) para armar escenarios y comprobar el estado real.
 * Los instantes se pasan como expresiones SQL fijas de la prueba (por ejemplo "now() - interval '1 hour'").
 */
final class DatosDb {

	static final String HACE_1_HORA = "now() - interval '1 hour'";
	static final String HACE_2_DIAS = "now() - interval '2 days'";
	static final String HACE_1_DIA = "now() - interval '1 day'";
	static final String EN_1_DIA = "now() + interval '1 day'";
	static final String NULO = "NULL";

	private final JdbcClient jdbc;

	DatosDb(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	UUID escuela(String slug) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.escuela (nombre, slug) VALUES (:nombre, :slug) RETURNING id
				""").param("nombre", "Escuela " + slug).param("slug", slug).query(UUID.class).single();
	}

	UUID escuelaPorSlugOCrear(String slug) {
		return jdbc.sql("SELECT id FROM gestion_patin.escuela WHERE lower(slug) = lower(:slug)")
				.param("slug", slug).query(UUID.class).optional().orElseGet(() -> escuela(slug));
	}

	UUID familia(UUID escuelaId, String nombre, boolean activa) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.familia (escuela_id, nombre_referencia, activa)
				VALUES (:e, :n, :a) RETURNING id
				""").param("e", escuelaId).param("n", nombre).param("a", activa).query(UUID.class).single();
	}

	UUID admin(UUID escuelaId, String email, boolean activo) {
		return usuario(escuelaId, null, email, "ADMIN", activo);
	}

	UUID usuarioFamilia(UUID escuelaId, UUID familiaId, String email) {
		return usuario(escuelaId, familiaId, email, "FAMILIA", true);
	}

	private UUID usuario(UUID escuelaId, UUID familiaId, String email, String rol, boolean activo) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.usuario (escuela_id, familia_id, nombre, apellido, email, password_hash, rol, activo)
				VALUES (:e, CAST(:f AS uuid), 'Nombre', 'Apellido', :email, '{noop}no-es-una-clave-real', :rol, :activo)
				RETURNING id
				""").param("e", escuelaId).param("f", familiaId).param("email", email).param("rol", rol)
				.param("activo", activo).query(UUID.class).single();
	}

	/** Inserta una invitacion pendiente por defecto; los demas casos usan {@link #invitacion}. */
	UUID invitacionPendiente(UUID escuelaId, UUID familiaId, UUID creadaPor, String tokenHash) {
		return invitacion(escuelaId, familiaId, creadaPor, tokenHash, HACE_1_HORA, EN_1_DIA, null, NULO, null, NULO);
	}

	UUID invitacion(UUID escuelaId, UUID familiaId, UUID creadaPor, String tokenHash, String creadoEn,
			String expiraEn, UUID usuarioId, String usadoEn, UUID revocadaPor, String revocadaEn) {
		String sql = """
				INSERT INTO gestion_patin.invitacion
				    (escuela_id, familia_id, creada_por, token_hash, creado_en, expira_en,
				     usuario_id, usado_en, revocada_por, revocada_en)
				VALUES (:e, :f, :c, :h, %s, %s, CAST(:u AS uuid), %s, CAST(:rp AS uuid), %s) RETURNING id
				""".formatted(creadoEn, expiraEn, usadoEn, revocadaEn);
		return jdbc.sql(sql).param("e", escuelaId).param("f", familiaId).param("c", creadaPor)
				.param("h", tokenHash).param("u", usuarioId).param("rp", revocadaPor).query(UUID.class).single();
	}

	boolean usada(UUID invitacionId) {
		return jdbc.sql("SELECT usado_en IS NOT NULL FROM gestion_patin.invitacion WHERE id = :id")
				.param("id", invitacionId).query(Boolean.class).single();
	}

	long contarUsuarios(UUID escuelaId, String rol) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.usuario WHERE escuela_id = :e AND rol = :r")
				.param("e", escuelaId).param("r", rol).query(Long.class).single();
	}

	/** Borra todo lo colgado de la escuela (en orden de dependencias); no borra la escuela. */
	void limpiarContenido(UUID escuelaId) {
		for (String tabla : new String[] { "auditoria", "invitacion", "usuario", "familia" }) {
			jdbc.sql("DELETE FROM gestion_patin." + tabla + " WHERE escuela_id = :e").param("e", escuelaId).update();
		}
	}

	void limpiarEscuela(UUID escuelaId) {
		limpiarContenido(escuelaId);
		jdbc.sql("DELETE FROM gestion_patin.escuela WHERE id = :e").param("e", escuelaId).update();
	}
}
