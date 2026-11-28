import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';

/** Fixed texts: the backend message is never echoed and no technical term reaches the person. */
export const DISABLED_TEXT = 'Completar tus datos desde acá todavía no está disponible. El club te avisa cuando puedas hacerlo.';
export const STALE_TEXT = 'Tus datos cambiaron mientras los editabas (por ejemplo, desde otro teléfono o por el club). Los actualizamos en pantalla: revisá y guardá de nuevo.';
export const LOCKED_IDENTITY_TEXT = 'El club ya verificó tu nombre y tu DNI. Si hay un error, avisale al club para que lo corrija.';
export const PERSON_UNAVAILABLE_TEXT = 'No pudimos asociarte con esa persona. Puede que ya esté asociada a otra cuenta. Actualizamos la lista.';
export const ALREADY_EXISTS_TEXT = 'Ya tenías tus datos cargados. Los mostramos en pantalla.';
export const CAP_TEXT = 'Tu familia ya cargó el máximo de adultos que se puede hacer desde acá. Avisale al club para que te ayude.';
export const DUPLICATE_TEXT = 'Ya hay una persona cargada con esos datos en tu familia. Revisá «Otros adultos» antes de volver a intentar.';
export const UNCERTAIN_TEXT = 'No pudimos confirmar si se guardó. Actualizamos la pantalla: revisá si figura tu cambio antes de volver a intentar.';

export type ProfileFailure = 'disabled' | 'stale' | 'locked' | 'cap' | 'duplicate' | 'person' | 'exists' | 'uncertain' | 'other';

export function classify(error: ApiError): ProfileFailure {
  if (error.code === 'AUTOSERVICIO_PERFIL_DESHABILITADO') return 'disabled';
  if (error.code === 'EDICION_DESACTUALIZADA') return 'stale';
  if (error.code === 'IDENTIDAD_VERIFICADA' || error.code === 'IDENTIDAD_CON_DNI_APROBADO') return 'locked';
  if (error.code === 'TOPE_TUTORES_ALCANZADO') return 'cap';
  if (error.code === 'TUTOR_DUPLICADO') return 'duplicate';
  if (error.code === 'PERSONA_NO_DISPONIBLE') return 'person';
  if (error.code === 'PERFIL_YA_EXISTE') return 'exists';
  if (error.status === 400 || error.status === 404 || error.status === 409 || error.kind === 'forbidden' || error.kind === 'csrf') return 'other';
  return 'uncertain';
}

export function failureText(failure: ProfileFailure, error: ApiError): string {
  switch (failure) {
    case 'disabled':
      return DISABLED_TEXT;
    case 'stale':
      return STALE_TEXT;
    case 'locked':
      return LOCKED_IDENTITY_TEXT;
    case 'cap':
      return CAP_TEXT;
    case 'duplicate':
      return DUPLICATE_TEXT;
    case 'person':
      return PERSON_UNAVAILABLE_TEXT;
    case 'exists':
      return ALREADY_EXISTS_TEXT;
    case 'uncertain':
      return UNCERTAIN_TEXT;
    default:
      return commonErrorMessage(error) ?? 'No pudimos guardar tus datos. Revisalos e intentá de nuevo.';
  }
}
