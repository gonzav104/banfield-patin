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

/* ---- Creation ---- */

export const REQUIRED_DNI = 'Ingresá el DNI.';
export const INVALID_DNI = 'El DNI debe tener entre 7 y 9 dígitos.';
export const REQUIRED_NAME = 'Ingresá el nombre.';
export const REQUIRED_LASTNAME = 'Ingresá el apellido.';
export const INVALID_CUIL = 'El CUIL debe tener 11 dígitos y un dígito verificador válido.';
export const INVALID_BIRTH_DATE = 'Ingresá una fecha de nacimiento válida y anterior a hoy.';
export const INVALID_EMAIL = 'Ingresá un correo electrónico válido.';
export const maxLengthText = (max: number) => `Máximo ${max} caracteres.`;

/** How a failed creation must be told to the administrator. */
export type CreateFailure = 'dniDuplicate' | 'dniReserved' | 'cuilDuplicate' | 'busy' | 'uncertain' | 'validation' | 'forbidden' | 'other';

export function classifyCreateFailure(error: ApiError): CreateFailure {
  if (error.status === 409) {
    if (error.code === 'DNI_DUPLICADO') return 'dniDuplicate';
    if (error.code === 'DNI_RESERVADO_POR_INACTIVO') return 'dniReserved';
    if (error.code === 'CUIL_DUPLICADO') return 'cuilDuplicate';
    return 'busy'; // any other conflict (a database contention): nothing was applied
  }
  // A network failure, a timeout and any 5xx (or an unreadable 2xx) may have been applied: never assume it was not.
  if (error.kind === 'network' || error.kind === 'unavailable' || error.kind === 'server') return 'uncertain';
  if (error.kind === 'validation') return 'validation';
  if (error.kind === 'forbidden') return 'forbidden';
  return 'other';
}

export const DNI_DUPLICATE_TEXT = 'Ya existe un deportista activo con ese DNI. Buscalo en el listado en lugar de crear uno nuevo.';
export const DNI_RESERVED_TEXT =
  'Ya existe un deportista inactivo con ese DNI y el DNI sigue reservado. Buscalo en el listado con el filtro «Inactivos»: no se puede crear uno nuevo con el mismo DNI.';
export const CUIL_DUPLICATE_TEXT = 'Ya existe un deportista con ese CUIL. Revisá el número o buscalo en el listado.';
/** Added to a duplicate answer that follows an attempt whose result could not be confirmed. */
export const DUPLICATE_AFTER_UNCERTAIN_TEXT =
  'Como el intento anterior no se pudo confirmar, es posible que ese deportista sea el que intentaste crear: no podemos saberlo.';
export const UNCERTAIN_TEXT =
  'No pudimos confirmar si el deportista se creó. Tus datos siguen acá. Antes de volver a intentar, verificá si ya figura en el listado.';

export function createErrorMessage(failure: CreateFailure, error: ApiError): string {
  switch (failure) {
    case 'busy':
      return 'Otra operación está en curso. Esperá unos segundos y volvé a intentarlo.';
    case 'validation':
      return 'Revisá los datos del deportista: el DNI, el nombre y el apellido son obligatorios y el resto debe respetar los formatos y límites.';
    case 'forbidden':
      return 'No tenés permiso para crear deportistas.';
    default:
      return commonErrorMessage(error) ?? 'No pudimos crear el deportista. Intentá de nuevo.';
  }
}

/**
 * Result of «Verificar en el listado»: a read-only search by DNI over every status, for the first (and only) page of at most 100
 * results. There is NO idempotency key in the backend, so a deportista with that DNI is only a hint (it may have been there before
 * or created by someone else), never proof that OUR request created it. Not seeing one proves nothing either: the request may still
 * be processed. And when the search returned more results than were inspected the check is INCOMPLETE and says so; it never
 * concludes anything from the part it did not see.
 */
export const VERIFY_FOUND =
  'Encontramos un deportista con ese DNI. Puede ser el que intentaste crear o uno que ya existía: no podemos confirmarlo. Abrilo para revisar sus datos.';
export const VERIFY_NOT_OBSERVED =
  'No observamos un deportista con ese DNI en este momento. Eso no prueba que la solicitud original no se haya aplicado: podría seguir procesándose y aparecer más tarde.';
export const verifyIncomplete = (inspected: number, total: number) =>
  `La verificación es incompleta: la búsqueda devolvió ${total} resultados y solo pudimos revisar ${inspected}. No podemos descartar que el deportista esté entre los que no revisamos. Buscalo en el listado antes de volver a intentar.`;
export const VERIFY_RETRY_NOTE =
  'Si volvés a intentar y el primer envío sí se había aplicado, el sistema lo rechazará por DNI duplicado: no se crea dos veces.';
export const VERIFY_ERROR = 'No pudimos verificar el listado ahora. Intentá de nuevo en unos instantes.';
