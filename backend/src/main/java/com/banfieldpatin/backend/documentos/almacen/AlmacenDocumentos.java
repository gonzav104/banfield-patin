package com.banfieldpatin.backend.documentos.almacen;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Puerto interno: no acepta rutas ni nombres originales y nunca entrega URLs. No es un DTO HTTP. */
public interface AlmacenDocumentos {
	Creado crear(byte[] contenido, String tipoContenido);
	void eliminarCreado(Creado creado);

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
