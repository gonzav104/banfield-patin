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
  'Ya existe un deportista inactivo con ese DNI y el DNI sigue reservado. Buscalo en el listado con el filtro «Inactivos» y reactivalo desde su detalle: no se puede crear uno nuevo con el mismo DNI.';
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

/* ---- Edit and status (U8). Fixed texts: the backend message is never echoed. ---- */

export const EDIT_SAVED_NOTICE = 'Cambios guardados.';

/** How a failed edit or status change must be told to the administrator. */
export type EditFailure = CreateFailure | 'stale' | 'missing';

export function classifyEditFailure(error: ApiError): EditFailure {
  if (error.status === 409 && error.code === 'EDICION_DESACTUALIZADA') return 'stale';
  if (error.status === 404) return 'missing';
  return classifyCreateFailure(error);
}

export const EDIT_DNI_DUPLICATE_TEXT = 'Ya existe otro deportista activo con ese DNI. Revisá el número.';
export const EDIT_DNI_RESERVED_TEXT =
  'Otro deportista inactivo tiene reservado ese DNI. Revisá el número, o buscalo en el listado con el filtro «Inactivos» y reactivalo desde su detalle.';
export const EDIT_CUIL_DUPLICATE_TEXT = 'Ya existe otro deportista con ese CUIL. Revisá el número.';
export const STALE_EDIT_TEXT =
  'Otro administrador pudo haber modificado este deportista mientras lo editabas. Tu borrador sigue acá, pero todavía no se guardó y el guardado queda bloqueado hasta que revises los datos vigentes.';
export const UNCERTAIN_EDIT_TEXT = 'No pudimos confirmar si el cambio se guardó. Tu borrador sigue acá. Verificá los datos vigentes antes de volver a intentar.';

export function updateAthleteErrorMessage(failure: EditFailure, error: ApiError): string {
  switch (failure) {
    case 'busy':
      return 'Otra operación está en curso sobre este deportista. Esperá unos segundos y volvé a intentarlo.';
    case 'validation':
      return 'Revisá los datos del deportista: el DNI, el nombre y el apellido son obligatorios y el resto debe respetar los formatos y límites.';
    case 'forbidden':
      return 'No tenés permiso para editar deportistas.';
    case 'missing':
      return 'El deportista ya no existe.';
    default:
      return commonErrorMessage(error) ?? 'No pudimos guardar el cambio. Intentá de nuevo.';
  }
}

export const EDIT_REVIEW_INTRO = 'Estos son los datos vigentes. Tu borrador no se guardó ni se mezcló con ellos.';
export const EDIT_NO_DIFFERENCES =
  'Los datos personales vigentes no tienen diferencias con los que viste al empezar: otra operación (por ejemplo un cambio de estado) movió la revisión.';
export const EDIT_CONTINUE_NOTE =
  'Al seguir editando, los campos que no modificaste pasan a los datos vigentes y los que modificaste conservan tu borrador. Nada se guarda hasta que toques «Guardar cambios».';
export const EDIT_CONFLICT_NOTE = 'Modificaste y otra persona también modificó estos campos: elegí qué valor conservar en cada uno para poder seguir.';
export const EDIT_MATCHES_DRAFT =
  'Los datos vigentes ya coinciden con tu borrador: no queda nada por guardar. No podemos saber si fue tu envío u otro cambio.';
export const EDIT_UNCHANGED =
  'No vemos cambios guardados al verificar. Si la solicitud original todavía se estuviera procesando, un nuevo intento se rechazaría por revisión obsoleta en lugar de aplicarse dos veces.';
/** Names of the saved data that the submission would remove (never their values). */
export const clearedFieldsText = (names: string[]) => `Al guardar se van a quitar estos datos: ${names.join(', ')}.`;

export const STALE_STATUS_TEXT = 'Otro administrador pudo haber modificado este deportista. Revisá los datos vigentes antes de decidir de nuevo.';
export const UNCERTAIN_STATUS_TEXT = 'No pudimos confirmar si el cambio de estado se aplicó. Verificá el estado vigente antes de volver a intentar.';
export const STATUS_VERIFY_UNCHANGED =
  'No vemos el cambio aplicado. La solicitud original podría seguir procesándose: si volvés a intentar y ya se había aplicado, se rechazará por revisión obsoleta en lugar de aplicarse dos veces.';
export const STATUS_VERIFY_MOVED =
  'El deportista cambió desde tu solicitud. Otra operación pudo haberlo modificado: no podemos saber si el cambio actual fue tu solicitud. Decidí de nuevo con el estado vigente.';

export function statusErrorMessage(failure: EditFailure, error: ApiError): string {
  switch (failure) {
    case 'busy':
      return 'Otra operación está en curso sobre este deportista. Esperá unos segundos y volvé a intentarlo.';
    case 'validation':
      return 'No pudimos procesar la solicitud. Recargá los datos vigentes y volvé a intentar.';
    case 'forbidden':
      return 'No tenés permiso para cambiar el estado de deportistas.';
    case 'missing':
      return 'El deportista ya no existe.';
    default:
      return commonErrorMessage(error) ?? 'No pudimos cambiar el estado. Intentá de nuevo.';
  }
}

/* ---- Documents (U16) ---- */

export const DOCUMENTS_TITLE = 'Documentos';
export const DOCUMENTS_NOTE =
  'Cada vez que se abre o se descarga un documento, el sistema de la escuela lo registra. Al descargar un PDF se guarda una copia en este dispositivo.';
export const DOCUMENTS_EMPTY = 'Este deportista no tiene documentos vigentes.';
export const DOCUMENTS_LOADING = 'Cargando los documentos…';
export const DOCUMENT_LOADING = 'Abriendo el documento…';
export const PDF_READY = 'PDF listo. Para verlo hay que descargarlo: queda una copia en este dispositivo.';

const DOCUMENT_TYPE_LABEL: Record<string, string> = {
  DNI_DEPORTISTA_FRENTE: 'DNI – frente',
  DNI_DEPORTISTA_DORSO: 'DNI – dorso',
  DNI_TUTOR_FRENTE: 'DNI del tutor – frente',
  DNI_TUTOR_DORSO: 'DNI del tutor – dorso',
  APTO_FISICO: 'Apto físico',
  SOLICITUD_LICENCIA: 'Solicitud de licencia',
  CERTIFICADO_WADA: 'Certificado WADA',
  OTRO: 'Otro',
};

/** A type this screen does not know yet is still listed, with a generic name. */
export const documentTypeLabel = (tipo: string): string => (Object.hasOwn(DOCUMENT_TYPE_LABEL, tipo) ? DOCUMENT_TYPE_LABEL[tipo]! : 'Documento');

export function documentsListErrorMessage(error: ApiError): string {
  if (error.kind === 'forbidden') return 'Esta cuenta no tiene permiso para ver los documentos.';
  if (error.kind === 'unauthenticated') return 'La sesión venció. Es necesario ingresar de nuevo.';
  if (error.kind === 'network') return 'No se pudo conectar con el servidor.';
  return 'No se pudieron cargar los documentos.';
}

export const DOCUMENT_UNSUPPORTED = 'El archivo recibido tiene un formato que no se puede mostrar.';

/** Fixed texts per code: the server message is never shown. A retry is always the administrator's choice (each read is audited). */
export function documentFileErrorMessage(error: ApiError): string {
  switch (error.code) {
    case 'INTEGRIDAD_FALLIDA':
      return 'El archivo guardado no superó la verificación de integridad, por eso no se muestra.';
    case 'INTEGRIDAD_NO_VERIFICABLE':
      return 'No es posible verificar la integridad de este documento, por eso no se muestra.';
    case 'DESCARGA_OCUPADA':
      return 'Hay otros documentos abriéndose en este momento. Se puede volver a intentar en unos instantes.';
    case 'DESCARGA_NO_DISPONIBLE':
      return 'La consulta de documentos no está disponible en este momento. Se puede volver a intentar más tarde.';
    case 'DOCUMENTO_NO_ENCONTRADO':
      return 'El documento ya no está disponible. Es posible que haya sido reemplazado.';
    default:
      break;
  }
  if (error.status === 404) return 'El documento ya no está disponible. Es posible que haya sido reemplazado.';
  if (error.status === 429) return 'Hay otros documentos abriéndose en este momento. Se puede volver a intentar en unos instantes.';
  if (error.kind === 'csrf') return 'No se pudo validar la solicitud. Es necesario recargar la página.';
  if (error.kind === 'forbidden') return 'Esta cuenta no tiene permiso para abrir este documento.';
  if (error.kind === 'unauthenticated') return 'La sesión venció. Es necesario ingresar de nuevo.';
  if (error.kind === 'network') return 'No se pudo conectar con el servidor.';
  if (error.kind === 'unavailable') return 'La consulta de documentos no está disponible en este momento. Se puede volver a intentar más tarde.';
  return 'No se pudo abrir el documento.';
}
