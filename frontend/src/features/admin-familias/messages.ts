import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';

/** Safe, fixed texts: the backend message is never echoed. */
export function listErrorMessage(error: ApiError): string {
  if (error.kind === 'forbidden') return 'No tenés permiso para ver las familias.';
  if (error.kind === 'validation') return 'Revisá los filtros e intentá de nuevo.';
  return commonErrorMessage(error) ?? 'No pudimos cargar las familias. Intentá de nuevo.';
}

export const NOT_FOUND_TITLE = 'No encontramos esta familia';
export const NOT_FOUND_TEXT = 'Puede que no exista o que el enlace no sea correcto. Volvé al listado para elegir otra.';

/** Detail: 404 and 400 (an id the backend cannot read) are one «not found» screen, not an error message. */
export function detailErrorMessage(error: ApiError): string {
  if (error.kind === 'forbidden') return 'No tenés permiso para ver esta familia.';
  return commonErrorMessage(error) ?? 'No pudimos cargar la familia. Intentá de nuevo.';
}

export function isNotFound(error: ApiError): boolean {
  return error.status === 404 || error.status === 400;
}

export const NAME_REQUIRED = 'Ingresá un nombre de referencia.';
export const NAME_TOO_LONG = 'Máximo 150 caracteres.';

/**
 * A write that ends in a network failure, a timeout or a server error may still have been applied: we say so and never
 * assume it failed. Everything else is a definite answer.
 */
export function createErrorMessage(error: ApiError): { text: string; uncertain: boolean } {
  if (error.kind === 'network' || error.kind === 'unavailable' || error.kind === 'server') {
    return {
      text: 'No pudimos confirmar si la familia se creó. Antes de volver a intentar, buscala en el listado para no duplicarla.',
      uncertain: true,
    };
  }
  if (error.kind === 'validation') return { text: 'Revisá el nombre de referencia: es obligatorio y admite hasta 150 caracteres.', uncertain: false };
  if (error.kind === 'forbidden') return { text: 'No tenés permiso para crear familias.', uncertain: false };
  if (error.kind === 'conflict') return { text: 'No pudimos crear la familia por un conflicto. Revisá el listado e intentá de nuevo.', uncertain: false };
  return { text: commonErrorMessage(error) ?? 'No pudimos crear la familia. Intentá de nuevo.', uncertain: false };
}

/** How a failed write (edit, activate, deactivate) must be told to the administrator. */
export type WriteFailure = 'stale' | 'inactive' | 'busy' | 'uncertain' | 'validation' | 'forbidden' | 'missing' | 'other';

export function classifyWriteFailure(error: ApiError): WriteFailure {
  if (error.status === 409) {
    if (error.code === 'EDICION_DESACTUALIZADA') return 'stale';
    if (error.code === 'FAMILIA_INACTIVA') return 'inactive';
    return 'busy'; // CONFLICTO_CONCURRENCIA: a database contention, nothing was applied
  }
  // A network failure, a timeout and any 5xx (or an unreadable 2xx) may have been applied: never assume it was not.
  if (error.kind === 'network' || error.kind === 'unavailable' || error.kind === 'server') return 'uncertain';
  if (error.kind === 'validation') return 'validation';
  if (error.kind === 'forbidden') return 'forbidden';
  if (error.status === 404) return 'missing';
  return 'other';
}

export const STALE_EDIT_TEXT = 'Otro administrador modificó esta familia mientras la editabas. Tu borrador sigue acá, pero todavía no se guardó.';
export const STALE_STATUS_TEXT = 'Otro administrador modificó esta familia. Revisá los datos vigentes antes de decidir de nuevo.';
export const UNCERTAIN_EDIT_TEXT = 'No pudimos confirmar si el cambio se guardó. Verificá los datos vigentes antes de volver a intentar.';
export const UNCERTAIN_STATUS_TEXT = 'No pudimos confirmar si el cambio se aplicó. Verificá los datos vigentes antes de volver a intentar.';

/** Fixed texts: the backend message is never echoed. `stale` and `uncertain` have their own panels. */
export function writeErrorMessage(failure: WriteFailure, error: ApiError, target: 'name' | 'status'): string {
  switch (failure) {
    case 'busy':
      return 'Otra operación está en curso sobre esta familia. Esperá unos segundos y volvé a intentarlo.';
    case 'validation':
      return target === 'name'
        ? 'Revisá el nombre de referencia: es obligatorio y admite hasta 150 caracteres.'
        : 'No pudimos procesar la solicitud. Recargá los datos vigentes y volvé a intentar.';
    case 'forbidden':
      return 'No tenés permiso para modificar familias.';
    case 'missing':
      return 'La familia ya no existe.';
    default:
      return commonErrorMessage(error) ?? 'No pudimos guardar el cambio. Intentá de nuevo.';
  }
}
