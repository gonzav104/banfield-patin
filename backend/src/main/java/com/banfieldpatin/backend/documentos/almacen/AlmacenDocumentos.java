package com.banfieldpatin.backend.documentos.almacen;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Puerto interno: no acepta rutas ni nombres originales y nunca entrega URLs. No es un DTO HTTP. */
public interface AlmacenDocumentos {
    DestinoPreparado prepararDestino();
    /** Stream de un solo uso, propiedad del llamador; éste debe cerrarlo, también ante error. */
    void crear(DestinoPreparado destino, InputStream contenido, long tamanioBytes, String tipoContenido);
    InspeccionAlmacen inspeccionar(DestinoPreparado destino);
	Creado crear(byte[] contenido, String tipoContenido);
	void eliminarCreado(Creado creado);

	/**
	 * Lee el objeto de un destino reconstruido desde el registro interno con
	 * {@link DestinoPreparado#desdeRegistro} y devuelve sus bytes completos sin interpretarlos. Nunca
	 * lee más de {@code maxBytes}: un objeto mayor, ausente o incompleto es un fallo
	 * ({@link FalloAlmacenDocumentos}). No verifica la integridad (tamaño exacto ni SHA-256): lo hace
	 * el llamador contra su manifiesto antes de entregar un solo byte. La lectura tiene un plazo total
	 * (no sólo de inactividad): al vencer se aborta la E/S y es un fallo {@code TIMEOUT}.
	 */
	ContenidoLeido leer(DestinoPreparado destino, long maxBytes);

	/** Capacidad de compensación de una creación confirmada, no reconstruible desde una clave persistida. */
	final class Creado {
		private final UUID clave;
		private final Object emisor;
		private final AtomicBoolean usado = new AtomicBoolean();

		Creado(UUID clave, Object emisor) {
			this.clave = clave;
			this.emisor = emisor;
		}

		/** Sólo para futura persistencia interna; nunca serializar al frontend. */
		public UUID claveInterna() { return clave; }

		boolean reclamar(Object emisor) { return this.emisor == emisor && usado.compareAndSet(false, true); }

		@Override
		public String toString() { return "Creado[privado]"; }
	}
}
