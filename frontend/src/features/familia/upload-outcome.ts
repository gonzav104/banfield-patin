import { ApiError } from '@/lib/api-error';

/**
 * What the screen does after a failed document upload (POST /api/familia/deportistas/{id}/documentos):
 * - `retry-same`: keep the SAME Idempotency-Key and the SAME normalized bytes. The server either stored the document once
 *   or not at all; retrying with the same key returns it (200) instead of storing it twice. Used whenever the outcome is
 *   unknown or temporary (lost response, timeout, 5xx, 429, stale CSRF token, or a 409 `OPERACION_EN_CURSO`: the previous
 *   request with that key still holds the reservation; the retry is manual, never automatic).
 * - `choose-other-file`: the file itself was rejected (size, format, content or the virus scan). The attempt is dropped.
 * - `refresh`: any other 409 (the operation cannot be repeated with that key: it is closed, does not match, or the
 *   document already exists; also an unknown 409 code) or 404. The attempt is dropped and the document list is reloaded.
 *   While replacing an OBSERVED document (U18) a 409 and a 404 DOCUMENTO_NO_ENCONTRADO have their own message; the
 *   original upload is unchanged.
 * - `session-expired` / `stop`: no session, or no permission. Nothing to retry.
 * The server message is never shown: every text here is written for the family.
 */
export type UploadAction = 'retry-same' | 'choose-other-file' | 'refresh' | 'session-expired' | 'stop';

export interface UploadFailure {
  action: UploadAction;
  message: string;
}

const UNCONFIRMED =
  'No pudimos confirmar si el documento se guardó. Tocá «Reintentar»: si ya se había guardado, no se va a duplicar.';

export function uploadFailure(error: unknown, maxBytes: number | null = null, replacing = false): UploadFailure {
  const failure = ApiError.from(error);
  const { status, code } = failure;

  if (failure.kind === 'network' || code === 'RESULTADO_CARGA_INCIERTO') return { action: 'retry-same', message: UNCONFIRMED };
  if (failure.kind === 'unauthenticated') return { action: 'session-expired', message: 'Tu sesión venció. Volvé a ingresar.' };
  if (failure.kind === 'csrf') {
    return { action: 'retry-same', message: 'No pudimos validar la sesión de seguridad. Tocá «Reintentar».' };
  }
  if (failure.kind === 'forbidden') return { action: 'stop', message: 'No tenés permiso para cargar documentos de este deportista.' };
  if (code === 'CARGA_NO_DISPONIBLE') {
    return { action: 'retry-same', message: 'La carga de documentos no está disponible en este momento. Probá de nuevo más tarde.' };
  }
  if (status === 429) return { action: 'retry-same', message: 'Hay muchas cargas en curso. Esperá unos segundos y tocá «Reintentar».' };
  if (status === 413) {
    const limit = maxBytes ? ` (${Math.floor(maxBytes / 1_000_000)} MB)` : '';
    return { action: 'choose-other-file', message: `El archivo supera el tamaño máximo${limit}. Elegí otro archivo o sacá una foto nueva.` };
  }
  if (status === 422 && code === 'ARCHIVO_PELIGROSO') {
    return { action: 'choose-other-file', message: 'El archivo no pasó el control de seguridad. Elegí otro archivo.' };
  }
  if (status === 422) {
    return { action: 'choose-other-file', message: 'No pudimos aceptar este archivo. Probá con una foto nueva o con un PDF sin contraseña.' };
  }
  if (status === 415 || status === 400) {
    return { action: 'choose-other-file', message: 'No pudimos aceptar este archivo. Elegí una foto JPG o PNG, o un PDF.' };
  }
  if (status === 409 && code === 'OPERACION_EN_CURSO') {
    return {
      action: 'retry-same',
      message: 'La carga anterior todavía se está procesando. Esperá unos minutos y tocá «Reintentar»: no se va a duplicar.',
    };
  }
  if (status === 409 && replacing) {
    return {
      action: 'refresh',
      message: 'Este documento ya fue reemplazado o cambió de estado. Actualizamos la información: si sigue observado, volvé a elegir el archivo.',
    };
  }
  if (status === 409) {
    return {
      action: 'refresh',
      message: 'No se pudo completar esta carga porque cambió el estado del documento. Actualizamos la información: si todavía falta, volvé a elegir el archivo.',
    };
  }
  if (replacing && code === 'DOCUMENTO_NO_ENCONTRADO') {
    return { action: 'refresh', message: 'Este documento ya no está disponible. Actualizamos la información.' };
  }
  if (status === 404) return { action: 'refresh', message: 'No encontramos este deportista. Volvé al listado.' };
  if (status !== null && status >= 500) return { action: 'retry-same', message: UNCONFIRMED };
  return { action: 'retry-same', message: 'No pudimos completar el envío. Tocá «Reintentar».' };
}
