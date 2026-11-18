package com.banfieldpatin.backend.db;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.vinculos.VinculoAdminService;
import com.banfieldpatin.backend.familias.vinculos.dto.DeportistaAVincular;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculacionRespuesta;
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

	/** El mismo ayudante con otro servicio (por ejemplo, uno con dependencias decoradas). */
	VinculosConRevision usando(VinculoAdminService otro) {
		return new VinculosConRevision(jdbc, otro);
	}

	long revision(UUID deportistaId) {
		return jdbc.sql("SELECT version_vinculos FROM gestion_patin.deportista WHERE id = :d").param("d", deportistaId)
				.query(Long.class).optional().orElse(0L);
	}

	List<DeportistaAVincular> vigentes(List<UUID> ids) {
		return ids.stream().map(id -> new DeportistaAVincular(id, revision(id))).toList();
	}

	VinculacionRespuesta vincular(UsuarioAutenticado admin, UUID familiaId, List<UUID> ids, DatosSolicitud datos) {
		return servicio.vincular(admin, familiaId, vigentes(ids), datos);
	}

	VinculoRespuesta revocar(UsuarioAutenticado admin, UUID familiaId, UUID deportistaId, DatosSolicitud datos) {
		return servicio.revocar(admin, familiaId, deportistaId, revision(deportistaId), datos);
	}

	VinculoRespuesta cambiarPrincipal(UsuarioAutenticado admin, UUID familiaId, UUID deportistaId,
			DatosSolicitud datos) {
		return servicio.cambiarPrincipal(admin, familiaId, deportistaId, revision(deportistaId), datos);
	}
}
