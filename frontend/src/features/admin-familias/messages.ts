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
