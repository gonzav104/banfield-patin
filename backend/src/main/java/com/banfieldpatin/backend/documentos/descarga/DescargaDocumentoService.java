package com.banfieldpatin.backend.documentos.descarga;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.documentos.almacen.AlmacenDocumentos;
import com.banfieldpatin.backend.documentos.almacen.DestinoPreparado;
import com.banfieldpatin.backend.documentos.almacen.FalloAlmacenDocumentos;
import com.banfieldpatin.backend.documentos.almacen.InspeccionAlmacen;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/**
 * Descarga auditada de un documento vigente para ADMIN (U16). Secuencia: autorizar y cargar el registro
 * en una transacción de lectura → obtener un permiso propio (sin cola) → leer el objeto acotado a su
 * tamaño registrado → verificar tamaño exacto y SHA-256 completo contra el manifiesto de la operación
 * publicada → registrar {@code DOCUMENTO_DESCARGADO} en una transacción propia confirmada → recién
 * entonces escribir los bytes. El permiso se libera al terminar la entrega o ante cualquier error.
 * <p>
 * D1: el contenido verificado vive sólo en memoria (acotado por el tamaño registrado y por el
 * semáforo); no se crea ningún archivo. El evento de auditoría acredita la autorización y el inicio de
 * la entrega, no que el cliente haya recibido el archivo completo.
 */
public class DescargaDocumentoService {

	/** Destino de los bytes verificados; lo provee la capa HTTP. */
	@FunctionalInterface
	public interface Entrega {
		void escribir(String tipoContenido, String nombreArchivo, byte[] contenido) throws IOException;
	}

	private static final Set<String> TIPOS_SERVIBLES = Set.of("application/pdf", "image/jpeg", "image/png");
	private final ConsultaDescargaJdbc consulta;
	private final TransactionTemplate lectura;
	private final TransactionTemplate auditada;
	private final AuditoriaService auditoria;
	private final ObjectProvider<AlmacenDocumentos> almacenes;
	private final Semaphore permisos;

	public DescargaDocumentoService(ConsultaDescargaJdbc consulta, PlatformTransactionManager tx,
			AuditoriaService auditoria, ObjectProvider<AlmacenDocumentos> almacenes, DescargaPropiedades propiedades) {
		this.consulta = consulta;
		this.lectura = new TransactionTemplate(tx);
		this.lectura.setReadOnly(true);
		this.auditada = new TransactionTemplate(tx);
		this.auditada.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.auditoria = auditoria;
		this.almacenes = almacenes;
		this.permisos = new Semaphore(propiedades.concurrencia());
	}

	public void descargar(UsuarioAutenticado usuario, UUID deportistaId, UUID documentoId, DatosSolicitud datos,
			Entrega entrega) {
		if (usuario.rol() != Rol.ADMIN) {
			throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado.");
		}
		DocumentoDescargable documento = lectura
				.execute(s -> consulta.buscar(usuario.escuelaId(), deportistaId, documentoId))
				.orElseThrow(DescargaDocumentoService::noEncontrado);
		if (documento.sha256() == null || !TIPOS_SERVIBLES.contains(documento.mime())) throw noVerificable();
		DestinoPreparado destino = destino(documento);
		AlmacenDocumentos almacen = almacenes.getIfAvailable();
		if (almacen == null) throw noDisponible();
		if (!permisos.tryAcquire()) {
			throw new ExcepcionNegocio(HttpStatus.TOO_MANY_REQUESTS, "DESCARGA_OCUPADA",
					"Hay otras descargas en curso. Reintentá en unos segundos.");
		}
		byte[] contenido = null;
		try {
			try {
				contenido = almacen.leer(destino, documento.tamanioBytes()).contenido();
			} catch (RuntimeException ex) {
				DiagnosticoDescarga.fallo("lectura", ex);
				if (tamanioDistintoConfirmado(almacen, destino, documento.tamanioBytes(), ex))
					throw integridadFallida(usuario, deportistaId, documentoId, documento, datos);
				throw noDisponible();
			}
			if (contenido.length != documento.tamanioBytes()
					|| !MessageDigest.isEqual(sha256(contenido), documento.sha256()))
				throw integridadFallida(usuario, deportistaId, documentoId, documento, datos);
			try {
				auditada.executeWithoutResult(s -> auditoria.registrar(evento(usuario, deportistaId, documentoId,
						documento, AccionAuditoria.DOCUMENTO_DESCARGADO, datos)));
			} catch (RuntimeException ex) {
				DiagnosticoDescarga.fallo("auditoria", ex);
				throw noDisponible();
			}
			try {
				entrega.escribir(documento.mime(), nombreNeutro(documento.tipo(), documento.mime()), contenido);
			} catch (IOException ex) {
				// El cliente cortó la conexión: ya no hay respuesta que enviar; sólo se libera el permiso.
				DiagnosticoDescarga.fallo("entrega", ex);
			}
		} finally {
			contenido = null;
			permisos.release();
		}
	}

	private static DestinoPreparado destino(DocumentoDescargable documento) {
		try {
			return DestinoPreparado.desdeRegistro(documento.bucket(), UUID.fromString(documento.clave()));
		} catch (RuntimeException ex) {
			throw noVerificable();
		}
	}

	/**
	 * Un objeto MAYOR que el registrado lo rechaza la lectura acotada como respuesta inesperada, igual que otras
	 * respuestas inválidas. Sólo en ese caso se consulta el estado del objeto (HEAD, operación existente del puerto):
	 * si confirma un tamaño distinto del registrado es un fallo de integridad, no una indisponibilidad. Cualquier otro
	 * resultado (incierto, ausente, mismo tamaño o un fallo de la propia consulta) sigue siendo 503.
	 */
	private static boolean tamanioDistintoConfirmado(AlmacenDocumentos almacen, DestinoPreparado destino, long tamanio,
			RuntimeException fallo) {
		if (!(fallo instanceof FalloAlmacenDocumentos f) || f.codigo() != FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA)
			return false;
		try {
			InspeccionAlmacen inspeccion = almacen.inspeccionar(destino);
			return inspeccion.estado() == InspeccionAlmacen.Estado.EXISTENCIA_CONFIRMADA
					&& inspeccion.tamanioBytes().isPresent() && inspeccion.tamanioBytes().getAsLong() != tamanio;
		} catch (RuntimeException ex) {
			DiagnosticoDescarga.fallo("inspeccion", ex);
			return false;
		}
	}

	/** Audita el fallo de integridad (un fallo secundario no reemplaza el 502) y devuelve el 502; nunca hay bytes. */
	private ExcepcionNegocio integridadFallida(UsuarioAutenticado usuario, UUID deportistaId, UUID documentoId,
			DocumentoDescargable documento, DatosSolicitud datos) {
		DiagnosticoDescarga.fallo("integridad", null);
		try {
			auditoria.registrarFallo(evento(usuario, deportistaId, documentoId, documento,
					AccionAuditoria.DOCUMENTO_INTEGRIDAD_FALLIDA, datos));
		} catch (RuntimeException ex) {
			// Un fallo secundario al auditar no reemplaza el 502 original; nunca se entregan los bytes.
			DiagnosticoDescarga.fallo("auditoria-integridad", ex);
		}
		return new ExcepcionNegocio(HttpStatus.BAD_GATEWAY, "INTEGRIDAD_FALLIDA",
				"El documento almacenado no coincide con el registrado.");
	}

	private static EventoAuditoria evento(UsuarioAutenticado usuario, UUID deportistaId, UUID documentoId,
			DocumentoDescargable documento, AccionAuditoria accion, DatosSolicitud datos) {
		return new EventoAuditoria(usuario.escuelaId(), usuario.id(), accion, "DOCUMENTO", documentoId,
				Map.of("deportistaId", deportistaId.toString(), "tipo", documento.tipo(),
						"tamanioBytes", documento.tamanioBytes()), datos);
	}

	private static byte[] sha256(byte[] contenido) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(contenido);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 no disponible", ex);
		}
	}

	/** Nombre de archivo sin datos de personas: sólo el tipo documental y la extensión del formato. */
	static String nombreNeutro(String tipo, String mime) {
		String base = switch (tipo) {
			case "DNI_DEPORTISTA_FRENTE", "DNI_TUTOR_FRENTE" -> "dni-frente";
			case "DNI_DEPORTISTA_DORSO", "DNI_TUTOR_DORSO" -> "dni-dorso";
			case "APTO_FISICO" -> "apto-fisico";
			case "SOLICITUD_LICENCIA" -> "solicitud-licencia";
			case "CERTIFICADO_WADA" -> "certificado-wada";
			default -> "documento";
		};
		String extension = switch (mime) {
			case "application/pdf" -> "pdf";
			case "image/jpeg" -> "jpg";
			case "image/png" -> "png";
			default -> throw noVerificable();
		};
		return base + "." + extension;
	}

	int permisosDisponibles() {
		return permisos.availablePermits();
	}

	private static ExcepcionNegocio noEncontrado() {
		return new ExcepcionNegocio(HttpStatus.NOT_FOUND, "DOCUMENTO_NO_ENCONTRADO", "Documento no encontrado.");
	}

	private static ExcepcionNegocio noVerificable() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "INTEGRIDAD_NO_VERIFICABLE",
				"No se puede verificar la integridad del documento.");
	}

	private static ExcepcionNegocio noDisponible() {
		return new ExcepcionNegocio(HttpStatus.SERVICE_UNAVAILABLE, "DESCARGA_NO_DISPONIBLE",
				"La descarga de documentos no está disponible en este momento.");
	}
}
