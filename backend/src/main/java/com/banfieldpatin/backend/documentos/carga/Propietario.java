package com.banfieldpatin.backend.documentos.carga;

import java.util.UUID;

/**
 * Dueño del documento que se carga (U19): un deportista o un tutor, exactamente uno. Es el único punto de extensión del
 * pipeline de carga: lo demás (preparación, escaneo, Storage, reservas, reconciliación) no distingue al dueño.
 */
record Propietario(UUID deportista, UUID tutor) {
  static Propietario deDeportista(UUID id) {
    return new Propietario(id, null);
  }

  static Propietario deTutor(UUID id) {
    return new Propietario(null, id);
  }

  boolean esTutor() {
    return tutor != null;
  }

  UUID id() {
    return esTutor() ? tutor : deportista;
  }

  /** Columna del dueño en {@code documento} y en {@code operacion_carga_documental} (valor fijo, nunca de entrada). */
  String columna() {
    return esTutor() ? "tutor_id" : "deportista_id";
  }
}
