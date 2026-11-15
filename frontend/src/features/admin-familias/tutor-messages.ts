import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';
import type { WriteFailure } from './messages';

export const TUTOR_NOT_FOUND_TITLE = 'No encontramos este tutor';
export const TUTOR_NOT_FOUND_TEXT = 'Puede que no exista, que no pertenezca a esta familia o que el enlace no sea correcto. Volvé a la familia para elegir otro.';

/** Fixed texts: the backend message is never echoed. 404 and 400 are one «not found» screen, not an error message. */
export function tutorDetailErrorMessage(error: ApiError): string {
  if (error.kind === 'forbidden') return 'No tenés permiso para ver este tutor.';
  return commonErrorMessage(error) ?? 'No pudimos cargar el tutor. Intentá de nuevo.';
}

export const REQUIRED_NAME = 'Ingresá el nombre.';
export const REQUIRED_LASTNAME = 'Ingresá el apellido.';
export const INVALID_DNI = 'El DNI debe tener entre 7 y 9 dígitos.';
export const INVALID_EMAIL = 'Ingresá un correo electrónico válido.';
export const maxLengthText = (max: number) => `Máximo ${max} caracteres.`;

export const FAMILY_INACTIVE_NOTICE = 'La familia está inactiva: no se pueden agregar tutores. Reactivala desde su detalle.';
export const STALE_TUTOR_TEXT = 'La familia cambió mientras completabas el formulario (otro administrador la modificó). Tus datos siguen acá, pero el tutor no se creó.';
export const INACTIVE_TUTOR_TEXT = 'La familia está inactiva: no se pueden agregar tutores. Reactivala desde su detalle y volvé a intentarlo. Tus datos siguen acá, pero el tutor no se creó.';
export const UNCERTAIN_TUTOR_TEXT = 'No pudimos confirmar si el tutor se creó. Antes de volver a intentar, verificá si ya figura en la familia: un reenvío podría crearlo dos veces.';

/** Texts of the failures of the creation that have no panel of their own (stale, inactive and uncertain do). */
export function createTutorErrorMessage(failure: WriteFailure, error: ApiError): string {
  switch (failure) {
    case 'busy':
      return 'Otra operación está en curso sobre esta familia. Esperá unos segundos y volvé a intentarlo.';
    case 'validation':
      return 'Revisá los datos del tutor: el nombre y el apellido son obligatorios y el resto debe respetar los límites.';
    case 'forbidden':
      return 'No tenés permiso para agregar tutores.';
    case 'missing':
      return 'La familia ya no existe.';
    default:
      return commonErrorMessage(error) ?? 'No pudimos agregar el tutor. Intentá de nuevo.';
  }
}

/**
 * Result of «Verificar si se creó». There is NO idempotency in the backend (the DNI is not unique and creating a tutor does not move
 * the family revision): a same-name tutor is only a hint, never proof that OUR request created it, and not finding it is not proof that
 * the request will not be applied later. The texts say exactly that.
 */
export const VERIFY_ONE_MATCH =
  'Encontramos un tutor nuevo con ese nombre y apellido. Puede ser el que intentaste crear o uno creado por otra persona: no podemos confirmarlo. Revisalo en la familia antes de volver a intentar.';
export const verifyManyMatches = (count: number) =>
  `Encontramos ${count} tutores nuevos con ese nombre y apellido: la coincidencia es ambigua y no podemos saber si alguno es el que intentaste crear. Revisalos en la familia antes de volver a intentar.`;
export const VERIFY_RESEND_BLOCKED = 'El reenvío sigue bloqueado para no duplicar el tutor.';
export const VERIFY_NO_MATCH = 'No encontramos el tutor al verificar. La solicitud original podría seguir procesándose y aparecer más tarde.';
export const VERIFY_DUPLICATE_WARNING =
  'Si volvés a intentar y la primera solicitud sí se estaba procesando, el tutor podría quedar duplicado. Revisá la familia antes de reenviar.';

/* ---- Edit (U5-B). Fixed texts: the backend message is never echoed. ---- */

export const TUTOR_SAVED_NOTICE = 'Cambios guardados.';
export const FAMILY_INACTIVE_EDIT_NOTICE = 'La familia está inactiva: no se pueden editar sus tutores. Reactivala desde su detalle.';
export const STALE_TUTOR_EDIT_TEXT =
  'El tutor o su familia fueron modificados mientras editabas (otro administrador los cambió). Tu borrador sigue acá, pero todavía no se guardó.';
export const INACTIVE_TUTOR_EDIT_TEXT =
  'La familia está inactiva: no se pueden editar sus tutores. Reactivala desde su detalle y volvé a intentarlo. Tu borrador sigue acá, pero no se guardó.';
export const UNCERTAIN_TUTOR_EDIT_TEXT = 'No pudimos confirmar si el cambio se guardó. Verificá los datos vigentes antes de volver a intentar.';
export const EDIT_REVIEW_INTRO = 'Estos son los datos vigentes. Tu borrador no se guardó ni se mezcló con ellos.';
export const EDIT_CONTINUE_NOTE =
  'Si seguís editando, los campos que no modificaste pasan a los datos vigentes y los que modificaste conservan tu borrador. Nada se guarda hasta que toques «Guardar cambios».';
export const EDIT_MATCHES_DRAFT =
  'Los datos vigentes ya coinciden con tu borrador: no queda nada por guardar. No podemos saber si fue tu envío u otro cambio.';
export const EDIT_UNCHANGED =
  'No vemos cambios guardados al verificar. Si la solicitud original todavía se estuviera procesando, un nuevo intento se rechazaría por revisión obsoleta en lugar de aplicarse dos veces.';
/** Names of the saved data that the submission would remove (never their values). */
export const clearedFieldsText = (names: string[]) => `Al guardar se van a quitar estos datos: ${names.join(', ')}.`;

/** Texts of the failures of the edit that have no panel of their own (stale, inactive and uncertain do). */
export function updateTutorErrorMessage(failure: WriteFailure, error: ApiError): string {
  switch (failure) {
    case 'busy':
      return 'Otra operación está en curso sobre esta familia. Esperá unos segundos y volvé a intentarlo.';
    case 'validation':
      return 'Revisá los datos del tutor: el nombre y el apellido son obligatorios y el resto debe respetar los límites.';
    case 'forbidden':
      return 'No tenés permiso para editar tutores.';
    case 'missing':
      return 'El tutor ya no existe.';
    default:
      return commonErrorMessage(error) ?? 'No pudimos guardar el cambio. Intentá de nuevo.';
  }
}
