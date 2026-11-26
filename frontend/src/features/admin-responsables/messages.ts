import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';
import type { ClosureCause } from './schemas';

/** Safe, fixed texts: the backend message is never echoed. Voseo, like the rest of the panel. */

/* ---- The way in from the athlete detail ---- */
export const ACCESS_TITLE = 'Responsables';
export const ACCESS_TEXT = 'Consultá qué tutores están registrados como responsables del deportista y su historial.';
export const ACCESS_LINK = 'Ver responsables';

/* ---- Screen ---- */
export const SCREEN_TITLE = 'Responsables';
export const SCREEN_LOADING = 'Cargando los responsables…';
export const BACK_TO_ATHLETE = 'Volver al detalle del deportista';
export const SCREEN_NOTE =
  'Un responsable es un tutor que el club registró de forma explícita para este deportista. El tipo es un dato declarado: no acredita representación legal ni da acceso a datos o documentos.';
export const CURRENT_TITLE = 'Responsables vigentes';
export const CURRENT_EMPTY = 'Este deportista no tiene responsables vigentes.';
export const HISTORY_TITLE = 'Historial';
export const HISTORY_SHOW = 'Ver historial';
export const HISTORY_HIDE = 'Ocultar historial';
export const HISTORY_EMPTY = 'Todavía no hay responsabilidades cerradas para este deportista.';
export const HISTORY_NOTE = 'Una responsabilidad cerrada no se reabre: restablecerla requiere una nueva confirmación.';
export const REFRESH_FAILED = 'No pudimos actualizar los responsables. Consultá el estado vigente antes de operar.';
export const REFRESH_RETRY = 'Consultar responsables vigentes';

/** Closure causes in plain language: who or what ended the responsibility. */
const CAUSES: Record<ClosureCause, string> = {
  REVOCACION_ADMIN: 'Revocada por el club',
  TUTOR_INACTIVO: 'El tutor se desactivó',
  FAMILIA_INACTIVA: 'La familia se desactivó',
  DEPORTISTA_INACTIVO: 'El deportista se desactivó',
  FAMILIA_DEPORTISTA_REVOCADA: 'Se revocó el vínculo de la familia con el deportista',
};
export const causeLabel = (cause: ClosureCause): string => CAUSES[cause];

export function responsablesErrorMessage(error: ApiError): string {
  if (error.kind === 'forbidden') return 'No tenés permiso para ver los responsables.';
  return commonErrorMessage(error) ?? 'No pudimos cargar los responsables. Intentá de nuevo.';
}
