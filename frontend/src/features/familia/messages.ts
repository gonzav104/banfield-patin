import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';
import type { SportsStatus } from './schemas';

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

/** Athlete sheet. One 404 screen for a foreign, non-existent or revoked athlete: it must not reveal which one it is. */
export const SHEET_NOT_FOUND_TITLE = 'No encontramos este deportista';
export const SHEET_NOT_FOUND_TEXT = 'Revisá el enlace o volvé al listado para elegir un deportista de tu familia.';
export const SHEET_LOADING = 'Cargando la ficha…';
export const SCHOOL_VS_SPORT_NOTE =
  '«Estado en la escuela» indica si el deportista figura activo o inactivo en la escuela. «Estado deportivo» es su situación en la temporada vigente. Son dos cosas distintas.';
/** Neutral: it says there is nothing to show, never WHY (the server does not tell, and neither do we). */
export const NO_CURRENT_SEASON_TEXT = 'No hay datos de la temporada vigente para mostrar.';
export const NOT_REPORTED = 'No informado';
/** Sports status as the server reports it. «Suspendido» is shown as it is, with no cause or explanation. */
export const SPORTS_STATUS_LABEL: Record<SportsStatus, string> = {
  ACTIVO: 'Activo',
  INACTIVO: 'Inactivo',
  SUSPENDIDO: 'Suspendido',
};
