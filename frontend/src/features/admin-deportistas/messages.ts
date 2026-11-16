import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';

/** Safe, fixed texts: the backend message is never echoed. */
export function listErrorMessage(error: ApiError): string {
  if (error.kind === 'forbidden') return 'No tenés permiso para ver los deportistas.';
  if (error.kind === 'validation') return 'Revisá los filtros e intentá de nuevo.';
  return commonErrorMessage(error) ?? 'No pudimos cargar los deportistas. Intentá de nuevo.';
}

export const NOT_FOUND_TITLE = 'No encontramos este deportista';
export const NOT_FOUND_TEXT = 'Puede que no exista o que el enlace no sea correcto. Volvé al listado para elegir otro.';

/** Detail: 404 and 400 (an id the backend cannot read) are one «not found» screen, not an error message. */
export function detailErrorMessage(error: ApiError): string {
  if (error.kind === 'forbidden') return 'No tenés permiso para ver este deportista.';
  return commonErrorMessage(error) ?? 'No pudimos cargar el deportista. Intentá de nuevo.';
}

export function isNotFound(error: ApiError): boolean {
  return error.status === 404 || error.status === 400;
}

export const PRIVATE_DATA_NOTE = 'Son datos personales: solo los ven los administradores.';
export const CREATED_NOTICE = 'Deportista creado. Todavía no tiene familia asignada.';
