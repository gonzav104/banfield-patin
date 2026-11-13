import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';

/** Copy for failures while loading family data. 404 FAMILIA_NO_ENCONTRADA has its own screen, not a message. */
export function familyErrorMessage(error: ApiError): string {
  if (error.kind === 'forbidden') return 'No tenés permiso para ver esta información.';
  return commonErrorMessage(error) ?? 'No pudimos cargar la información. Intentá de nuevo.';
}

export const NO_FAMILY_TITLE = 'Tu cuenta todavía no está asociada a una familia';
export const NO_FAMILY_TEXT = 'Consultá con la escuela para que la vinculen.';
export const FAMILY_UNAVAILABLE_TITLE = 'No pudimos encontrar tu familia';
export const FAMILY_UNAVAILABLE_TEXT = 'Consultá con la escuela.';
export const NO_ATHLETES_TITLE = 'Todavía no hay deportistas vinculados a tu familia';
export const NO_ATHLETES_TEXT = 'La escuela los vincula. Cuando lo haga, los vas a ver acá.';
export const ATHLETE_STATE_NOTE =
  '«Activo» o «Inactivo» es el estado del deportista en la escuela. No indica su inscripción a una temporada ni a la federación.';
