import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';

/** Safe, fixed texts: the backend message is never echoed. */

export const SECTION_TITLE = 'Datos deportivos';
export const SECTION_TEXT = 'Consultá el historial deportivo del deportista, año por año.';
export const SECTION_LINK = 'Ver datos deportivos';
export const LOADING_TEXT = 'Cargando los datos deportivos…';

/** The two planes the administrator must not mix: the state in the school (global) and the state in each season. */
export const SCHOOL_STATE_NOTE =
  '«Estado en la escuela» indica si el deportista figura activo o inactivo en la escuela. «Estado deportivo» es su situación en cada temporada. Son dos cosas distintas.';

export const NO_SEASONS_TITLE = 'Todavía no hay temporadas';
export const NO_SEASONS_TEXT = 'La escuela todavía no tiene temporadas cargadas, así que no hay datos deportivos para mostrar. Cuando existan, los datos de cada año se verán acá.';
export const NO_ROWS_TITLE = 'Este deportista todavía no tiene datos deportivos';
export const NO_ROWS_TEXT = 'No hay datos deportivos cargados para ninguna temporada.';

export const SEASON_NOT_FOUND_TITLE = 'No encontramos esta temporada';
export const SEASON_NOT_FOUND_TEXT = 'Puede que no exista o que el enlace no sea correcto. Volvé al historial deportivo para elegir otra.';
export const NO_ROW_TITLE = 'Sin datos deportivos en esta temporada';
export const NO_ROW_TEXT = 'El deportista no tiene datos deportivos cargados para esta temporada. Volvé al historial deportivo para ver las temporadas que sí tienen.';

export function sportsErrorMessage(error: ApiError): string {
  if (error.kind === 'forbidden') return 'No tenés permiso para ver los datos deportivos.';
  return commonErrorMessage(error) ?? 'No pudimos cargar los datos deportivos. Intentá de nuevo.';
}

/** What a failed read of ONE season row means. `season` is also what an answer that cannot be told apart is treated as. */
export type RowAbsence = 'athlete' | 'season' | 'row';

/** 404 (and 400, an id the backend cannot read) are «not found» screens, told apart by the backend's own code; anything else is an error. */
export function classifyRowAbsence(error: ApiError): RowAbsence | null {
  if (error.status !== 404 && error.status !== 400) return null;
  if (error.code === 'DEPORTISTA_NO_ENCONTRADO') return 'athlete';
  if (error.code === 'DEPORTISTA_TEMPORADA_NO_ENCONTRADA') return 'row';
  return 'season';
}

/** The history belongs to one athlete: any 404 or 400 means that athlete cannot be shown. */
export function isAthleteAbsent(error: ApiError): boolean {
  return error.status === 404 || error.status === 400;
}
