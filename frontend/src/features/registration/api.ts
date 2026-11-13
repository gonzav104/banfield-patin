import { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiWrite } from '@/lib/http';

const publicInvitationSchema = z.object({
  valida: z.boolean(),
  escuelaNombre: z.string(),
  emailSugerido: z.string().nullable().optional(),
  expiraEn: z.string(),
});
export type PublicInvitation = z.infer<typeof publicInvitationSchema>;

export interface RegistrationInput {
  token: string;
  nombre: string;
  apellido: string;
  email: string;
  password: string;
}

/**
 * The token travels in the request body, never in a URL or query string.
 * Any unusable invitation (unknown, expired, revoked, used, malformed) yields the same 400 INVITACION_NO_DISPONIBLE:
 * the backend deliberately does not say which one.
 */
export async function validateInvitation(token: string): Promise<PublicInvitation> {
  const result = publicInvitationSchema.safeParse(await apiWrite('post', '/auth/invitaciones/validar', { token }));
  if (!result.success || !result.data.valida) {
    throw new ApiError('validation', 'La invitación no está disponible.', { status: 400, code: 'INVITACION_NO_DISPONIBLE' });
  }
  return result.data;
}

/** Creates the FAMILIA account. The backend answers 201 without opening a session. */
export async function registerFamily(input: RegistrationInput): Promise<void> {
  await apiWrite('post', '/auth/registro/invitacion', input);
}

export function isInvitationUnavailable(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400 && error.code === 'INVITACION_NO_DISPONIBLE';
}
