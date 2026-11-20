import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';
import { SEASON_YEAR_MAX, SEASON_YEAR_MIN, type SeasonYearResult } from './season-form';

/* Fixed texts: the backend message is never echoed. */
export const SEASONS_TITLE = 'Temporadas';
export const SEASONS_INTRO = 'Una temporada agrupa los datos deportivos de un año. Solo una puede estar activa: las familias ven los datos de la temporada activa y de las anteriores a ella.';
export const SEASONS_LOADING = 'Cargando temporadas…';
export const NO_SEASONS_TITLE = 'Todavía no hay temporadas';
export const NO_SEASONS_TEXT = 'Creá la primera con el formulario de arriba. Una temporada nueva queda inactiva hasta que la actives.';
export const NO_ACTIVE_TEXT = 'Ninguna temporada está activa. Las familias no ven datos deportivos hasta que actives una.';
export const ACTIVE_HEADING = 'Temporada activa';
export const OTHER_HEADING = 'Otras temporadas';
export const ACTIVE_LABEL = 'Activa';
export const INACTIVE_LABEL = 'Inactiva';
export const REFRESH_FAILED_TEXT = 'No pudimos actualizar las temporadas. Consultá el estado vigente antes de operar.';
export const CONSULT_SEASONS = 'Consultar temporadas vigentes';

export function seasonsReadMessage(error: ApiError): string {
  if (error.kind === 'forbidden') return 'No tenés permiso para ver las temporadas.';
  return commonErrorMessage(error) ?? 'No pudimos cargar las temporadas. Intentá de nuevo.';
}

/* ---- Creation ---- */
export const CREATE_HEADING = 'Crear una temporada';
export const YEAR_LABEL = 'Año de la temporada';
export const YEAR_HINT = `Un año entero entre ${SEASON_YEAR_MIN} y ${SEASON_YEAR_MAX}. La temporada nueva queda inactiva.`;
export const CREATE_SUBMIT = 'Crear temporada';
export const CREATE_SUBMITTING = 'Creando…';
export const DUPLICATE_YEAR_TEXT = 'Ya existe una temporada de ese año.';
export const INVALID_CREATE_TEXT = 'El año no es válido. Revisalo e intentá de nuevo.';
export const BUSY_CREATE_TEXT = 'No se pudo crear la temporada en este momento. No se creó nada: podés volver a intentarlo.';
export const FORBIDDEN_WRITE_TEXT = 'No tenés permiso para gestionar temporadas.';
export const UNCERTAIN_CREATE_TEXT = 'No podemos confirmar si la temporada se creó. No se reintentará automáticamente: consultá las temporadas vigentes antes de volver a intentar.';
export const CONSULTED_TEXT = 'Consultamos las temporadas vigentes. La consulta no confirma el resultado de la petición anterior.';
export const CONSULT_FAILED_TEXT = 'No pudimos consultar las temporadas vigentes. No se reintentó la operación. Podés volver a consultar.';
export const createdText = (year: number) => `Temporada ${year} creada. Queda inactiva hasta que la actives.`;

export function yearErrorText(result: Extract<SeasonYearResult, { ok: false }>): string {
  if (result.reason === 'empty') return 'Ingresá el año de la temporada.';
  if (result.reason === 'range') return `El año debe estar entre ${SEASON_YEAR_MIN} y ${SEASON_YEAR_MAX}.`;
  return 'Ingresá un año entero, solo con números.';
}

export type CreateFailure = 'duplicate' | 'validation' | 'busy' | 'forbidden' | 'uncertain' | 'other';

export function classifyCreateFailure(error: ApiError): CreateFailure {
  if (error.status === 409) return error.code === 'TEMPORADA_ANIO_DUPLICADO' ? 'duplicate' : 'busy';
  if (error.kind === 'validation') return 'validation';
  if (error.kind === 'forbidden') return 'forbidden';
  // A network failure, a timeout, any 5xx and an unreadable 2xx may have been applied: never assume it was not.
  if (error.kind === 'network' || error.kind === 'unavailable' || error.kind === 'server' || error.kind === 'unknown') return 'uncertain';
  return 'other';
}

export function createErrorMessage(failure: CreateFailure, error: ApiError): string {
  switch (failure) {
    case 'validation': return INVALID_CREATE_TEXT;
    case 'busy': return BUSY_CREATE_TEXT;
    case 'forbidden': return FORBIDDEN_WRITE_TEXT;
    case 'uncertain': return UNCERTAIN_CREATE_TEXT;
    default: return commonErrorMessage(error) ?? 'No pudimos crear la temporada. Intentá de nuevo.';
  }
}

/* ---- Activation ---- */
export const ACTIVATE_ACTION = 'Activar';
export const ACTIVATE_CONFIRM = 'Confirmar activación';
export const WAITING_TEXT = 'Esperando respuesta…';
export const WRITE_IN_PROGRESS_TEXT = 'Hay una operación de temporadas en curso. Esperá su respuesta antes de confirmar otra.';
export const activateTitle = (year: number) => `Activar la temporada ${year}`;
export const activatedText = (year: number) => `La temporada ${year} es la activa.`;
export const WILL_ACTIVATE = (year: number) => `Se activará la temporada ${year}.`;
export const WILL_DEACTIVATE = (year: number) => `Dejará de estar activa la temporada ${year}.`;
export const NONE_ACTIVE_NOW = 'Ahora ninguna temporada está activa.';
export const HISTORY_KEPT_TEXT = 'Los datos históricos de las otras temporadas no se eliminan ni se trasladan: solo cambia cuál es la vigente. Las familias pasarán a ver los datos de la temporada activa y de las anteriores a ella.';
export const STALE_TEXT = 'La temporada activa cambió mientras decidías. No se activó nada. Consultá las temporadas vigentes y revisá una nueva confirmación.';
export const BUSY_ACTIVATE_TEXT = 'Otra activación está en curso. No se confirmó ningún cambio de esta petición. Consultá las temporadas vigentes antes de volver a confirmar.';
export const UNCERTAIN_ACTIVATE_TEXT = 'No podemos confirmar el resultado de la activación. No se reintentará automáticamente; consultá las temporadas vigentes.';
export const SEASON_GONE_TEXT = 'La temporada ya no existe. Consultá las temporadas vigentes.';
export const REJECTED_ACTIVATE_TEXT = 'No se pudo autorizar la activación. Consultá las temporadas vigentes antes de volver a confirmar.';
export const ALREADY_ACTIVE_TEXT = 'Esa temporada ya es la activa. No hace falta confirmar nada.';
export const SEASON_MISSING_TEXT = 'La temporada ya no figura en el listado vigente.';
export const REVIEW_AGAIN = 'Revisar nueva confirmación';

export type ActivateFailure = 'stale' | 'busy' | 'gone' | 'uncertain' | 'rejected';

export function classifyActivateFailure(error: ApiError): ActivateFailure {
  if (error.code === 'EDICION_DESACTUALIZADA') return 'stale';
  if (error.code === 'CONFLICTO_CONCURRENCIA') return 'busy';
  if (error.status === 404) return 'gone';
  if (error.kind === 'network' || error.kind === 'unavailable' || error.kind === 'server' || error.kind === 'unknown') return 'uncertain';
  return 'rejected';
}

export function activateFailureText(failure: ActivateFailure): string {
  switch (failure) {
    case 'stale': return STALE_TEXT;
    case 'busy': return BUSY_ACTIVATE_TEXT;
    case 'gone': return SEASON_GONE_TEXT;
    case 'uncertain': return UNCERTAIN_ACTIVATE_TEXT;
    default: return REJECTED_ACTIVATE_TEXT;
  }
}
