import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';

export const DNI_IMAGE_NOTE = 'Aprobar una imagen de DNI no verifica la identidad: verificá la persona solo si los datos declarados son correctos.';
export const OBSERVE_WARNING = 'La familia va a ver este motivo. No incluyas datos personales que no hagan falta.';
export const OBSERVE_MAX = 500;

export const DOCUMENT_STATE_TEXT: Record<string, string> = {
  PENDIENTE: 'Pendiente',
  CARGADO: 'Cargado, sin revisar',
  APROBADO: 'Aprobado',
  OBSERVADO: 'Observado',
  VENCIDO: 'Vencido',
};
export const documentState = (state: string | null | undefined) => (state ? (DOCUMENT_STATE_TEXT[state] ?? 'En revisión') : 'Sin cargar');

/** The individual outcome of every tutor in a batch, in plain words. */
export const BATCH_RESULT_TEXT: Record<string, string> = {
  VERIFICADO: 'Verificado',
  YA_VERIFICADO: 'Ya estaba verificado',
  EDICION_DESACTUALIZADA: 'No se verificó: los datos cambiaron mientras los revisabas',
  TUTOR_NO_ENCONTRADO: 'No se verificó: la persona ya no existe',
  ESTADO_INCOMPATIBLE: 'No se verificó: su estado actual no lo permite',
  FAMILIA_INACTIVA: 'No se verificó: la familia está inactiva',
  OCUPADO: 'No se verificó: estaba ocupado, intentá de nuevo en un momento',
};
export const batchResultText = (result: string) => BATCH_RESULT_TEXT[result] ?? 'No se verificó: respuesta no reconocida';
export const batchResultOk = (result: string) => result === 'VERIFICADO' || result === 'YA_VERIFICADO';

export type DecisionFailure = 'stale' | 'incompatible' | 'inactive' | 'gone' | 'other';

export function classify(error: ApiError): DecisionFailure {
  if (error.code === 'EDICION_DESACTUALIZADA') return 'stale';
  if (error.code === 'ESTADO_INCOMPATIBLE') return 'incompatible';
  if (error.code === 'FAMILIA_INACTIVA') return 'inactive';
  if (error.code === 'TUTOR_NO_ENCONTRADO' || error.status === 404) return 'gone';
  return 'other';
}

/** Fixed texts: neither the server message nor the reason typed is ever echoed. */
export function failureText(failure: DecisionFailure, error: ApiError): string {
  switch (failure) {
    case 'stale':
      return 'Los datos de esa persona cambiaron mientras los revisabas. Actualizamos la lista: revisalos de nuevo.';
    case 'incompatible':
      return 'Esa persona ya no está en un estado que permita esta decisión. Actualizamos la lista.';
    case 'inactive':
      return 'La familia de esa persona está inactiva, así que no se puede verificar. Actualizamos la lista.';
    case 'gone':
      return 'Esa persona ya no existe o no está disponible. Actualizamos la lista.';
    default:
      if (error.kind === 'validation') return 'Revisá la decisión e intentá de nuevo.';
      return commonErrorMessage(error) ?? 'No pudimos guardar la decisión. Intentá de nuevo.';
  }
}

export const LIST_ERROR_TEXT = 'No pudimos cargar los tutores por verificar. Intentá de nuevo.';
