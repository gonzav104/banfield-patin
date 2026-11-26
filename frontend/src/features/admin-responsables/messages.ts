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

/* ---- Alta ---- */
export const ADD_BUTTON = 'Agregar responsable';
export const ADD_TITLE = 'Agregar responsable';
export const ADD_INACTIVE_NOTE = 'El deportista está inactivo: para agregar responsables, reactivalo primero.';
export const ADD_CANDIDATES_LOADING = 'Buscando tutores elegibles…';
export const ADD_CANDIDATES_INTRO = 'Elegí un tutor de las familias con vínculo activo con el deportista. Solo aparecen tutores activos que todavía no son responsables.';
export const ADD_CANDIDATES_EMPTY = 'No hay tutores elegibles. Revisá que el deportista tenga una familia con vínculo activo y que esa familia tenga tutores activos que aún no sean responsables.';
export const ADD_CANDIDATES_LEGEND = 'Tutores elegibles';
export const ADD_CONTINUE = 'Continuar';
export const ADD_OBSERVING = 'Verificando los datos vigentes…';
export const ADD_TIPO_LABEL = 'Tipo de responsable (opcional)';
export const ADD_TIPO_HELP = 'Es un dato declarado: no acredita representación legal ni da acceso a datos o documentos. Podés dejarlo sin declarar.';
export const ADD_CONFIRM = 'Confirmar alta';
export const ADD_SENDING = 'Esperando respuesta…';
export const ADD_DONE = 'Responsable agregado.';
export const ADD_DONE_REPLAY = 'Esa solicitud ya se había procesado: el responsable quedó agregado y hoy ya no figura entre los vigentes.';
export const ADD_BACK = 'Volver a elegir';
export const ADD_RETRY_SAME = 'Reintentar la misma solicitud';
export const ADD_CONSULT = 'Consultar responsables vigentes';
export const ADD_UNCERTAIN =
  'No pudimos confirmar si el responsable se agregó. Reintentá la misma solicitud: si ya se había registrado no se duplica. Mientras tanto no se puede agregar otro igual.';
export const ADD_CONFLICT = 'Otra operación estaba en curso y no se registró nada. Podés reintentar la misma solicitud.';
export const ADD_STALE = 'Los datos cambiaron mientras decidías. Volvé a elegir para ver el estado vigente: no se agregó nada.';
export const ADD_DISABLED = 'El alta de responsables no está habilitada por el momento. No se agregó nada.';
export const ADD_REJECTED = 'No se pudo agregar el responsable. Consultá el estado vigente antes de volver a intentar.';
export const ADD_CONSULTED = 'Consultamos el estado vigente. El responsable ya figura en la lista y no hace falta repetir la solicitud.';
export const ADD_PENDING_BLOCK = 'Hay un alta sin confirmar para este deportista. Resolvela antes de iniciar otra.';

/* ---- Edit and revoke ---- */
export const EDIT_BUTTON = 'Cambiar tipo';
export const EDIT_TITLE = 'Cambiar el tipo declarado';
export const EDIT_LABEL = 'Tipo de responsable';
export const EDIT_CONFIRM = 'Guardar tipo';
export const EDIT_DONE = 'Tipo actualizado.';
export const EDIT_UNCHANGED = 'Elegí un tipo distinto del actual.';
export const REVOKE_BUTTON = 'Revocar';
export const REVOKE_TITLE = 'Revocar responsable';
export const REVOKE_EXPLAIN = 'Revocar cierra esta responsabilidad. Es irreversible: no se reabre ni al reactivar al tutor, y volver a agregarlo requiere una nueva confirmación. No cambia el acceso de la familia ni sus documentos.';
export const REVOKE_CONFIRM = 'Confirmar revocación';
export const REVOKE_DONE = 'Responsable revocado.';
export const WRITE_STALE = 'El responsable cambió mientras decidías. Consultá el estado vigente: no se aplicó ningún cambio.';
export const WRITE_GONE = 'El responsable ya no está vigente. Consultamos el estado actual: no se aplicó ningún cambio.';
export const WRITE_UNCERTAIN = 'No podemos confirmar el resultado de la operación. No se reintenta automáticamente; consultá los responsables vigentes.';
export const WRITE_CONSULTED = 'Estado vigente consultado. La consulta no confirma el resultado de la petición anterior.';
export const WRITE_REJECTED = 'No se pudo aplicar el cambio. Consultá el estado vigente antes de volver a confirmar.';
export const WRITE_BUSY = 'Hay una operación de responsables en curso. Esperá su respuesta antes de confirmar otra.';

