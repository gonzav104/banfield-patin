package com.banfieldpatin.backend.documentos.descarga;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Autorización y carga en UNA consulta: el documento debe ser del deportista de la ruta, de la escuela
 * de la sesión y vigente. La operación de carga publicada que lo creó aporta el SHA-256 del manifiesto
 * sólo si su destino, tipo MIME y tamaño coinciden con los del documento; si no la hay, el SHA queda
 * null y la integridad no es verificable.
 */
class ConsultaDescargaJdbc {
	private static final String SQL = """
			SELECT d.tipo, d.storage_bucket, d.storage_key, d.mime_type, d.tamanio_bytes, o.sha256_archivo
			FROM gestion_patin.documento d
			LEFT JOIN gestion_patin.operacion_carga_documental o
			  ON o.documento_id = d.id AND o.escuela_id = d.escuela_id AND o.estado = 'PUBLICADA'
			 AND o.storage_bucket = d.storage_bucket AND o.objeto_id::text = d.storage_key
			 AND o.mime_type = d.mime_type AND o.tamanio_bytes = d.tamanio_bytes
			WHERE d.id = :documento AND d.deportista_id = :deportista AND d.escuela_id = :escuela AND d.vigente
			""";
	private final JdbcClient jdbc;

	ConsultaDescargaJdbc(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<DocumentoDescargable> buscar(UUID escuela, UUID deportista, UUID documento) {
		return jdbc.sql(SQL).param("documento", documento).param("deportista", deportista).param("escuela", escuela)
				.query((rs, n) -> new DocumentoDescargable(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getLong(5), rs.getBytes(6)))
				.optional();
	}
}
