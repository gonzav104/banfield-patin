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
		return usuarioFamilia(escuelaId, familiaId, email, true);
	}

	UUID usuarioFamilia(UUID escuelaId, UUID familiaId, String email, boolean activo) {
		return usuario(escuelaId, familiaId, email, "FAMILIA", activo);
	}

	// ---------- activacion / desactivacion con SQL directo (escenarios de sesion vigente, REQ-XC-09) ----------

	void desactivarUsuario(UUID usuarioId) {
		cambiarEstado("usuario", "activo", usuarioId, false);
	}

	void reactivarUsuario(UUID usuarioId) {
		cambiarEstado("usuario", "activo", usuarioId, true);
	}

	void desactivarEscuela(UUID escuelaId) {
		cambiarEstado("escuela", "activa", escuelaId, false);
	}

	void reactivarEscuela(UUID escuelaId) {
		cambiarEstado("escuela", "activa", escuelaId, true);
	}

	void desactivarFamilia(UUID familiaId) {
		cambiarEstado("familia", "activa", familiaId, false);
	}

	void reactivarFamilia(UUID familiaId) {
		cambiarEstado("familia", "activa", familiaId, true);
	}

	/** Borra la fila del usuario (solo sirve si nada la referencia, por ejemplo sin filas de auditoria). */
	void borrarUsuario(UUID usuarioId) {
		jdbc.sql("DELETE FROM gestion_patin.usuario WHERE id = :id").param("id", usuarioId).update();
	}

	/** Tabla y columna son literales de esta clase, nunca entrada externa. */
	private void cambiarEstado(String tabla, String columna, UUID id, boolean valor) {
		int filas = jdbc.sql("UPDATE gestion_patin." + tabla + " SET " + columna + " = :v WHERE id = :id")
				.param("v", valor).param("id", id).update();
		if (filas != 1) {
			throw new IllegalStateException("Se esperaba cambiar 1 fila de " + tabla + " y cambio " + filas);
		}
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

	/** Inserta un factor TOTP con SQL directo; confirmadoEn es una expresion SQL fija (p. ej. NULO o HACE_1_HORA). */
	void usuarioMfa(UUID usuarioId, UUID escuelaId, byte[] secretoCifrado, String confirmadoEn, Long ultimoPaso) {
		jdbc.sql("""
				INSERT INTO gestion_patin.usuario_mfa (usuario_id, escuela_id, secreto_cifrado, confirmado_en, ultimo_paso_usado)
				VALUES (:u, :e, :s, %s, :p)
				""".formatted(confirmadoEn)).param("u", usuarioId).param("e", escuelaId).param("s", secretoCifrado)
				.param("p", ultimoPaso).update();
	}

	boolean usada(UUID invitacionId) {
		return jdbc.sql("SELECT usado_en IS NOT NULL FROM gestion_patin.invitacion WHERE id = :id")
				.param("id", invitacionId).query(Boolean.class).single();
	}

	long contarUsuarios(UUID escuelaId, String rol) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.usuario WHERE escuela_id = :e AND rol = :r")
				.param("e", escuelaId).param("r", rol).query(Long.class).single();
	}

	// ---------- deportistas, tutores y vinculos (REQ-XC-07, V4) ----------

	/** Deportista activo con los datos minimos obligatorios de V1 (nombre, apellido, dni). */
	UUID deportista(UUID escuelaId, String dni, String nombre, String apellido) {
		return deportista(escuelaId, dni, nombre, apellido, true);
	}

	UUID deportista(UUID escuelaId, String dni, String nombre, String apellido, boolean activo) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.deportista (escuela_id, dni, nombre, apellido, activo)
				VALUES (:e, :dni, :n, :a, :activo) RETURNING id
				""").param("e", escuelaId).param("dni", dni).param("n", nombre).param("a", apellido)
				.param("activo", activo).query(UUID.class).single();
	}

	/** Deportista activo con CUIL (el CUIL es unico por escuela cuando existe). */
	UUID deportistaConCuil(UUID escuelaId, String dni, String cuil, String nombre, String apellido) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.deportista (escuela_id, dni, cuil, nombre, apellido)
				VALUES (:e, :dni, :cuil, :n, :a) RETURNING id
				""").param("e", escuelaId).param("dni", dni).param("cuil", cuil).param("n", nombre).param("a", apellido)
				.query(UUID.class).single();
	}

	/** Tutor activo de la familia, sin usuario asociado. */
	UUID tutor(UUID escuelaId, UUID familiaId, String nombre, String apellido) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.tutor (escuela_id, familia_id, nombre, apellido)
				VALUES (:e, :f, :n, :a) RETURNING id
				""").param("e", escuelaId).param("f", familiaId).param("n", nombre).param("a", apellido)
				.query(UUID.class).single();
	}

	/**
	 * Vinculo familia-deportista con SQL directo. {@code autorizado_en = now()} si y solo si hay autorizadoPor, de
	 * modo que tambien se pueden armar filas invalidas (por ejemplo ACTIVO sin autorizacion) para probar V4.
	 */
	UUID vinculo(UUID escuelaId, UUID familiaId, UUID deportistaId, String estado, boolean esPrincipal,
			UUID autorizadoPor) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.familia_deportista
				    (escuela_id, familia_id, deportista_id, estado, es_principal, autorizado_por, autorizado_en)
				VALUES (:e, :f, :d, :estado, :p, CAST(:ap AS uuid),
				        CASE WHEN CAST(:ap AS uuid) IS NULL THEN NULL ELSE now() END)
				RETURNING id
				""").param("e", escuelaId).param("f", familiaId).param("d", deportistaId).param("estado", estado)
				.param("p", esPrincipal).param("ap", autorizadoPor).query(UUID.class).single();
	}

	/** Borra todo lo colgado de la escuela (en orden de dependencias); no borra la escuela. */
	void limpiarContenido(UUID escuelaId) {
		for (String tabla : new String[] { "auditoria", "invitacion", "usuario_mfa", "familia_deportista", "tutor",
				"deportista", "usuario", "familia" }) {
			jdbc.sql("DELETE FROM gestion_patin." + tabla + " WHERE escuela_id = :e").param("e", escuelaId).update();
		}
	}

	void limpiarEscuela(UUID escuelaId) {
		limpiarContenido(escuelaId);
		jdbc.sql("DELETE FROM gestion_patin.escuela WHERE id = :e").param("e", escuelaId).update();
	}
}
