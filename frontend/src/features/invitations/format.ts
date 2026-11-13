import type { InvitationStatus } from './schemas';

export const STATUS_LABEL: Record<InvitationStatus, string> = {
  PENDIENTE: 'Pendiente',
  USADA: 'Usada',
  REVOCADA: 'Revocada',
  EXPIRADA: 'Vencida',
};

const formatter = new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' });

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : formatter.format(date);
}
