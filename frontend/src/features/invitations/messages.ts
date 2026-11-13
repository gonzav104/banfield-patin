import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';

export function createInvitationErrorMessage(error: ApiError): string {
  if (error.status === 404 && error.code === 'FAMILIA_NO_ENCONTRADA') {
    return 'La familia elegida ya no existe o está inactiva. Elegí otra.';
  }
  if (error.kind === 'validation') return error.message || 'Revisá los datos de la invitación.';
  if (error.kind === 'forbidden') return 'No tenés permiso para crear invitaciones.';
  return commonErrorMessage(error) ?? 'No pudimos crear la invitación. Intentá de nuevo.';
}

export function revokeErrorMessage(error: ApiError): string {
  if (error.status === 409 && error.code === 'INVITACION_NO_REVOCABLE') {
    return 'Esta invitación ya fue utilizada y no puede revocarse.';
  }
  if (error.status === 404) return 'La invitación ya no existe.';
  if (error.kind === 'forbidden') return 'No tenés permiso para revocar invitaciones.';
  return commonErrorMessage(error) ?? 'No pudimos revocar la invitación. Intentá de nuevo.';
}
