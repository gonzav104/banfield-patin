import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';

/** Safe, fixed texts: the backend message is never echoed. */
export function listErrorMessage(error: ApiError): string {
  if (error.kind === 'forbidden') return 'No tenés permiso para ver las familias.';
  if (error.kind === 'validation') return 'Revisá los filtros e intentá de nuevo.';
  return commonErrorMessage(error) ?? 'No pudimos cargar las familias. Intentá de nuevo.';
}
