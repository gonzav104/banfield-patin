import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';

/** Fixed texts: the backend message and what the person typed are never echoed, and no technical term reaches the person. */
export const DISABLED_TEXT = 'Cargar o cambiar otros adultos desde acá todavía no está disponible. Podés ver a quienes ya están cargados. El club te avisa cuando puedas hacerlo.';
export const NO_ACCESS_TEXT = 'Cargar a una persona acá no le da acceso a los deportistas ni le asigna responsabilidades: eso lo define el club.';
export const CAP_REACHED_TEXT = 'Tu familia ya cargó el máximo de adultos que se puede hacer desde acá. Si necesitás sumar a otra persona, avisale al club.';
export const LOCKED_IDENTITY_TEXT = 'Estos datos ya los confirmó el club. Para cambiarlos, avisale al club.';
export const OWN_ACCOUNT_TEXT = 'Esta persona tiene su propia cuenta y actualiza sus datos ella misma.';
export const UNCERTAIN_TEXT = 'No pudimos confirmar si se guardó. Actualizamos la lista: revisá si figura antes de volver a intentar. Si no está, podés guardar de nuevo sin riesgo de que quede repetida.';

export const STATE_TEXT: Record<string, string> = {
  DECLARADO: 'Pendiente de revisión del club',
  VERIFICADO: 'Verificado por el club',
  OBSERVADO: 'El club pidió una corrección',
};

export type AdultFailure = 'disabled' | 'cap' | 'duplicate' | 'stale' | 'account' | 'locked' | 'dniApproved' | 'keyReused' | 'gone' | 'uncertain' | 'other';

export function classify(error: ApiError): AdultFailure {
  switch (error.code) {
    case 'AUTOSERVICIO_TUTORES_DESHABILITADO':
      return 'disabled';
    case 'TOPE_TUTORES_ALCANZADO':
      return 'cap';
    case 'TUTOR_DUPLICADO':
      return 'duplicate';
    case 'EDICION_DESACTUALIZADA':
      return 'stale';
    case 'TUTOR_CON_CUENTA':
      return 'account';
    case 'IDENTIDAD_VERIFICADA':
      return 'locked';
    case 'IDENTIDAD_CON_DNI_APROBADO':
      return 'dniApproved';
    case 'IDEMPOTENCY_KEY_REUTILIZADA':
      return 'keyReused';
    case 'TUTOR_NO_ENCONTRADO':
      return 'gone';
    default:
      break;
  }
  if (error.status === 400 || error.status === 404 || error.status === 409 || error.kind === 'forbidden' || error.kind === 'csrf') return 'other';
  return 'uncertain';
}

export function failureText(failure: AdultFailure, error: ApiError): string {
  switch (failure) {
    case 'disabled':
      return DISABLED_TEXT;
    case 'cap':
      return CAP_REACHED_TEXT;
    case 'duplicate':
      return 'Ya hay un adulto cargado con esos datos en tu familia. Revisá la lista antes de agregarlo.';
    case 'stale':
      return 'Estos datos cambiaron mientras los editabas (por ejemplo, otra persona de tu familia o el club). Los actualizamos en pantalla: revisalos y guardá de nuevo.';
    case 'account':
      return OWN_ACCOUNT_TEXT;
    case 'locked':
    case 'dniApproved':
      return LOCKED_IDENTITY_TEXT;
    case 'keyReused':
      return 'No pudimos guardar porque esta carga cambió a mitad de camino. Revisá los datos e intentá de nuevo.';
    case 'gone':
      return 'Esa persona ya no figura en tu familia. Actualizamos la lista.';
    case 'uncertain':
      return UNCERTAIN_TEXT;
    default:
      return commonErrorMessage(error) ?? 'No pudimos guardar los datos. Revisalos e intentá de nuevo.';
  }
}
