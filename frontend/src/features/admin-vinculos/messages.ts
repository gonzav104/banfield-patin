import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';
import type { LinkState } from './schemas';

/** Safe, fixed texts: the backend message is never echoed. Voseo, like the rest of the panel. */

/* ---- The way in from the athlete detail ---- */
export const ACCESS_TITLE = 'Familias vinculadas';
export const ACCESS_TEXT = 'Consultá con qué familias está vinculado el deportista y cuál es la principal.';
export const ACCESS_LINK = 'Ver familias vinculadas';

/* ---- Screen of the athlete ---- */
export const ATHLETE_TITLE = 'Familias vinculadas';
export const ATHLETE_LOADING = 'Cargando los vínculos…';
export const BACK_TO_ATHLETE = 'Volver al detalle del deportista';
export const ATHLETE_ACCESS_NOTE = 'Solo un vínculo activo le da acceso a la familia.';
export const ATHLETE_INACTIVE_NOTE = 'Un deportista inactivo conserva sus vínculos activos: su familia lo sigue viendo, marcado como inactivo.';
export const NO_PRINCIPAL_NOTE = 'Ninguna de las familias activas está marcada como principal.';
export const ATHLETE_NO_LINKS = 'Este deportista todavía no está vinculado con ninguna familia.';
export const ATHLETE_NO_ACTIVE_LINKS = 'No tiene familias con vínculo activo.';

/* ---- Section of the family ---- */
export const FAMILY_SECTION_TITLE = 'Deportistas vinculados';
export const FAMILY_LOADING = 'Cargando los deportistas vinculados…';
export const FAMILY_ACCESS_NOTE = 'Solo un vínculo activo les da acceso a los deportistas.';
export const FAMILY_INACTIVE_NOTE = 'La familia está inactiva: no entra al portal aunque sus vínculos sigan activos.';
export const FAMILY_NO_LINKS = 'Esta familia todavía no tiene deportistas vinculados.';
export const FAMILY_NO_ACTIVE_LINKS = 'Esta familia no tiene deportistas con vínculo activo.';

/* ---- Shared ---- */
export const ACTIVE_GROUP = 'Vínculos activos';
export const PREVIOUS_GROUP = 'Vínculos anteriores';
/** The three ideas are always told apart with their own text: state in the school, state of the link, principal. */
export const PRINCIPAL_FAMILY = 'Familia principal';
export const PRINCIPAL_ATHLETE = 'Principal de este deportista';
export const INACTIVE_ATHLETE = 'Deportista inactivo';

export const LINK_STATE_LABEL: Record<LinkState, string> = {
  ACTIVO: 'Vínculo activo',
  REVOCADO: 'Vínculo revocado',
  PENDIENTE: 'Vínculo pendiente',
  RECHAZADO: 'Vínculo rechazado',
};

export function athleteLinksErrorMessage(error: ApiError): string {
  if (error.kind === 'forbidden') return 'No tenés permiso para ver los vínculos.';
  return commonErrorMessage(error) ?? 'No pudimos cargar los vínculos. Intentá de nuevo.';
}

export function familyAthletesErrorMessage(error: ApiError): string {
  if (error.kind === 'forbidden') return 'No tenés permiso para ver los deportistas vinculados.';
  return commonErrorMessage(error) ?? 'No pudimos cargar los deportistas vinculados. Intentá de nuevo.';
}

/** The links of an athlete belong to ONE athlete: any 404 or 400 (an id the backend cannot read) is one «not found» screen, never an error message. */
export function isAthleteAbsent(error: ApiError): boolean {
  return error.status === 404 || error.status === 400;
}
