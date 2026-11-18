package com.banfieldpatin.backend.db;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.vinculos.VinculoAdminService;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Llama al servicio de vinculos como un cliente que ACABA de leer la pantalla: cada llamada lee la revision vigente de los
 * vinculos del deportista (en su propia sentencia, fuera de la transaccion del servicio) y la envia como
 * {@code versionEsperada}. Un deportista inexistente se pide con revision 0. Las pruebas que necesitan una revision
 * concreta (obsoleta o anticipada) llaman al servicio directamente.
 */
final class VinculosConRevision {

	private final JdbcClient jdbc;
	private final VinculoAdminService servicio;

	VinculosConRevision(JdbcClient jdbc, VinculoAdminService servicio) {
		this.jdbc = jdbc;
		this.servicio = servicio;
	}

	long revision(UUID deportistaId) {
		return jdbc.sql("SELECT version_vinculos FROM gestion_patin.deportista WHERE id = :d").param("d", deportistaId)
				.query(Long.class).optional().orElse(0L);
	}

	VinculoRespuesta revocar(UsuarioAutenticado admin, UUID familiaId, UUID deportistaId, DatosSolicitud datos) {
		return servicio.revocar(admin, familiaId, deportistaId, revision(deportistaId), datos);
	}

	VinculoRespuesta cambiarPrincipal(UsuarioAutenticado admin, UUID familiaId, UUID deportistaId,
			DatosSolicitud datos) {
		return servicio.cambiarPrincipal(admin, familiaId, deportistaId, revision(deportistaId), datos);
	}
}
