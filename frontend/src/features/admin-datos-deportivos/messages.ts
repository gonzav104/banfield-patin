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

/* ---- Loading sports data (U10-B). Fixed texts: the backend message is never echoed. ---- */

export const LOAD_LINK = 'Cargar datos de una temporada';
export const LOAD_TITLE = 'Cargar datos deportivos';
export const LOAD_INTRO = 'Elegí una temporada en la que este deportista todavía no tenga datos. Solo se muestran las que se pueden cargar.';
export const LOAD_SUBMIT = 'Cargar datos';
export const LOAD_SUBMITTING = 'Cargando…';
export const LOADED_NOTICE = 'Datos deportivos cargados.';

export const INACTIVE_NOTE = 'Un deportista inactivo no admite datos deportivos nuevos. Reactivalo desde su detalle.';
export const INACTIVE_TITLE = 'Este deportista está inactivo';
export const INACTIVE_TEXT = 'No se le pueden cargar datos deportivos nuevos mientras esté inactivo. Reactivalo desde su detalle.';
export const INACTIVE_LINK = 'Ir al detalle del deportista';

export const NO_SEASONS_TO_LOAD_TEXT = 'La escuela todavía no tiene temporadas, así que no hay dónde cargar datos deportivos.';
export const ALL_USED_TITLE = 'Ya hay datos en todas las temporadas';
export const ALL_USED_TEXT = 'Este deportista ya tiene datos deportivos cargados en cada temporada de la escuela. Volvé al historial para consultarlos.';

export const NUMBER_TAKEN_TEXT = 'Ese número de corredor ya lo tiene otro deportista en esa temporada. Elegí otro o dejalo vacío.';
export const DUPLICATE_TEXT = 'Este deportista ya tiene datos deportivos en esa temporada. Ya no figura entre las que se pueden cargar.';
export const SEASON_GONE_TEXT = 'Esa temporada ya no existe. Elegí otra.';
export const BUSY_TEXT = 'Otra operación está en curso sobre este deportista. Esperá unos segundos y volvé a intentarlo.';
export const INACTIVE_ATHLETE_TEXT = 'Este deportista está inactivo, así que no se le pueden cargar datos deportivos. Reactivalo desde su detalle.';
export const INVALID_DATA_TEXT = 'Revisá los datos: la temporada es obligatoria y el resto debe respetar los formatos y límites.';
export const LOAD_FORBIDDEN_TEXT = 'No tenés permiso para cargar datos deportivos.';
export const UNCERTAIN_LOAD_TEXT = 'No pudimos confirmar si los datos se cargaron. Tus datos siguen acá. Antes de volver a intentar, verificá si la temporada ya los tiene.';
/** Added to a duplicate answer that follows an attempt whose result could not be confirmed. */
export const DUPLICATE_AFTER_UNCERTAIN_TEXT = 'Como el intento anterior no se pudo confirmar, es posible que esos datos sean los que intentaste cargar: no podemos saberlo.';

export const VERIFY_BUTTON = 'Verificar la temporada';
export const VERIFYING = 'Verificando…';
export const VERIFY_ERROR_TEXT = 'No pudimos verificar la temporada ahora. Intentá de nuevo en unos instantes.';
export const VERIFY_FOUND_TEXT = 'La temporada ya tiene estos datos cargados:';
export const VERIFY_SAME_TEXT = 'Coinciden con lo que enviaste, pero no podemos saber si los cargó este envío u otro.';
export const VERIFY_DIFFERENT_TEXT = 'Difieren de lo que enviaste: otra persona u otro envío pudo cargarlos.';
export const VERIFY_ABSENT_TEXT = 'No encontramos datos cargados en esa temporada en este momento. Esa lectura no descarta que el envío anterior se confirme más tarde.';
export const VERIFY_RETRY_TEXT = 'Si volvés a intentar y el primer envío sí se había aplicado, el sistema lo rechazará por duplicado: no se carga dos veces.';

/** How a failed load must be told to the administrator. */
export type LoadFailure = 'numberTaken' | 'duplicate' | 'inactive' | 'busy' | 'seasonGone' | 'athleteGone' | 'uncertain' | 'validation' | 'forbidden' | 'other';

export function classifyLoadFailure(error: ApiError): LoadFailure {
  if (error.status === 409) {
    if (error.code === 'NUMERO_CORREDOR_DUPLICADO') return 'numberTaken';
    if (error.code === 'DEPORTISTA_TEMPORADA_DUPLICADA') return 'duplicate';
    if (error.code === 'DEPORTISTA_INACTIVO') return 'inactive';
    return 'busy'; // any other conflict (a database contention): nothing was applied
  }
  if (error.status === 404) return error.code === 'TEMPORADA_NO_ENCONTRADA' ? 'seasonGone' : 'athleteGone';
  // A network failure, a timeout, any 5xx and an unreadable 2xx may have been applied: never assume it was not.
  if (error.kind === 'network' || error.kind === 'unavailable' || error.kind === 'server') return 'uncertain';
  if (error.kind === 'validation') return 'validation';
  if (error.kind === 'forbidden') return 'forbidden';
  return 'other';
}

/** The alert of a failure (the ones that have a field or a screen of their own are not here). */
export function loadErrorMessage(failure: LoadFailure, error: ApiError): string {
  switch (failure) {
    case 'busy':
      return BUSY_TEXT;
    case 'inactive':
      return INACTIVE_ATHLETE_TEXT;
    case 'seasonGone':
      return SEASON_GONE_TEXT;
    case 'validation':
      return INVALID_DATA_TEXT;
    case 'forbidden':
      return LOAD_FORBIDDEN_TEXT;
    case 'uncertain':
      return UNCERTAIN_LOAD_TEXT;
    default:
      return commonErrorMessage(error) ?? 'No pudimos cargar los datos deportivos. Intentá de nuevo.';
  }
}

/* ---- Editing sports data (U10-C). Fixed texts: the backend message is never echoed. ---- */

export const EDIT_LINK = 'Editar datos deportivos';
export const EDIT_TITLE = 'Editar datos deportivos';
export const EDIT_INTRO = 'Los datos se guardan solo cuando tocás «Guardar cambios». La temporada y el deportista no se pueden cambiar. El estado en la escuela se cambia desde el detalle del deportista.';
export const EDITED_NOTICE = 'Cambios guardados.';
export const EDIT_SUBMIT = 'Guardar cambios';
export const EDIT_SUBMITTING = 'Guardando…';
export const EDIT_NOTHING_TO_SAVE = 'No hay cambios para guardar.';

export const STALE_ROW_TEXT =
  'Otra persona pudo haber modificado estos datos mientras los editabas. Tu borrador sigue acá, pero todavía no se guardó y el guardado queda bloqueado hasta que revises los datos vigentes.';
export const UNCERTAIN_EDIT_TEXT = 'No pudimos confirmar si el cambio se guardó. Tu borrador sigue acá. Verificá los datos vigentes antes de volver a intentar.';
export const BUSY_EDIT_TEXT = 'Otra operación está en curso sobre estos datos. Esperá unos segundos y volvé a intentarlo. No se guardó nada.';
export const INVALID_EDIT_TEXT = 'Revisá los datos: el estado deportivo es obligatorio y el resto debe respetar los formatos y límites.';
export const EDIT_FORBIDDEN_TEXT = 'No tenés permiso para editar datos deportivos.';

/** How a failed edit must be told to the administrator. */
export type EditFailure = 'stale' | 'numberTaken' | 'busy' | 'athleteGone' | 'seasonGone' | 'rowGone' | 'uncertain' | 'validation' | 'forbidden' | 'other';

export function classifyEditFailure(error: ApiError): EditFailure {
  if (error.status === 409) {
    if (error.code === 'EDICION_DESACTUALIZADA') return 'stale';
    if (error.code === 'NUMERO_CORREDOR_DUPLICADO') return 'numberTaken';
    return 'busy'; // any other conflict (CONFLICTO_CONCURRENCIA: the lock could not be had): nothing was applied
  }
  if (error.status === 404) {
    if (error.code === 'DEPORTISTA_NO_ENCONTRADO') return 'athleteGone';
    if (error.code === 'DEPORTISTA_TEMPORADA_NO_ENCONTRADA') return 'rowGone';
    return 'seasonGone';
  }
  // A network failure, a timeout, any 5xx and an unreadable 2xx may have been applied: never assume it was not.
  if (error.kind === 'network' || error.kind === 'unavailable' || error.kind === 'server') return 'uncertain';
  if (error.kind === 'validation') return 'validation';
  if (error.kind === 'forbidden') return 'forbidden';
  return 'other';
}

/** The alert of a failure (the ones that have a field or a screen of their own are not here). */
export function editErrorMessage(failure: EditFailure, error: ApiError): string {
  switch (failure) {
    case 'stale':
      return STALE_ROW_TEXT;
    case 'uncertain':
      return UNCERTAIN_EDIT_TEXT;
    case 'busy':
      return BUSY_EDIT_TEXT;
    case 'validation':
      return INVALID_EDIT_TEXT;
    case 'forbidden':
      return EDIT_FORBIDDEN_TEXT;
    default:
      return commonErrorMessage(error) ?? 'No pudimos guardar los cambios. Intentá de nuevo.';
  }
}

export const clearedFieldsText = (labels: string[]) =>
  `Se van a quitar estos datos guardados: ${labels.join(', ')}. El guardado reemplaza todos los datos de la temporada y lo que dejes en blanco se borra.`;

export const REVIEW_INTRO = 'Estos son los datos que tiene la temporada ahora. Revisá qué cambió antes de seguir.';
export const REVIEW_NO_DIFFERENCES = 'Los datos vigentes son los mismos que viste al abrir: la revisión cambió pero ningún dato.';
export const REVIEW_MATCHES_DRAFT = 'Los datos vigentes coinciden con tu borrador, pero no podemos saber si los guardó tu envío u otro cambio. No hace falta volver a guardar.';
export const REVIEW_CONFLICT_NOTE = 'Cambiaron los dos: elegí con qué valor seguir en cada campo.';
export const REVIEW_CONTINUE_NOTE =
  'Al seguir, tu borrador se completa con los datos vigentes y el próximo guardado usa la revisión vigente. Nada se guarda hasta que toques «Guardar cambios».';
export const VERIFY_UNCHANGED_TEXT =
  'La revisión no cambió: no vemos cambios guardados. Eso no descarta del todo que el envío se haya aplicado: un cambio sin efecto no mueve la revisión.';
export const VERIFY_UNCHANGED_RETRY_TEXT = 'Si volvés a intentar con la misma revisión y el primer envío sí se había aplicado, el sistema lo rechazará: no se guarda dos veces.';
export const KIND_TEXT = {
  incorporated: 'Cambió otra persona: se incorpora el valor vigente',
  mine: 'Solo cambiaste vos: se conserva tu valor',
  coincide: 'Los dos lo cambiaron igual',
  conflict: 'Conflicto: los dos lo cambiaron distinto',
} as const;
/** When VERIFYING an uncertain result nobody can say WHO changed what: the texts only describe how the current data compare with the draft. */
export const KIND_TEXT_VERIFY = {
  incorporated: 'Cambió el valor vigente desde que abriste: se incorpora',
  mine: 'Solo cambió tu borrador: se conserva tu valor',
  coincide: 'El valor vigente ya coincide con tu borrador',
  conflict: 'El valor vigente y tu borrador son distintos: elegí uno',
} as const;
