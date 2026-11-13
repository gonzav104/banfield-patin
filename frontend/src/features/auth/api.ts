import { z } from 'zod';
import { ApiError, isApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';

export const authUserSchema = z.object({
  id: z.string(),
  nombre: z.string(),
  apellido: z.string(),
  email: z.string(),
  rol: z.enum(['ADMIN', 'FAMILIA']),
  escuelaId: z.string().nullable().optional(),
  familiaId: z.string().nullable().optional(),
  mfaPendiente: z.boolean(),
  mfaEnrolado: z.boolean(),
});

export type AuthUser = z.infer<typeof authUserSchema>;

const mfaEnrollmentSchema = z.object({ otpauthUri: z.string(), secretoBase32: z.string() });
export type MfaEnrollment = z.infer<typeof mfaEnrollmentSchema>;

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) {
    throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  }
  return result.data;
}

export interface Credentials {
  email: string;
  password: string;
}

/** Restores the session from the HttpOnly cookie. `null` means there is no valid session (401). */
export async function fetchSession(): Promise<AuthUser | null> {
  try {
    return parse(authUserSchema, await apiGet('/auth/me'));
  } catch (error) {
    if (isApiError(error, 'unauthenticated')) return null;
    throw error;
  }
}

export async function loginFamily(credentials: Credentials): Promise<AuthUser> {
  return parse(authUserSchema, await apiWrite('post', '/auth/login', credentials));
}

export async function loginAdmin(credentials: Credentials): Promise<AuthUser> {
  return parse(authUserSchema, await apiWrite('post', '/auth/admin/login', credentials));
}

export async function logout(): Promise<void> {
  try {
    await apiWrite('post', '/auth/logout');
  } catch (error) {
    // Already unauthenticated: the goal of logging out is met.
    if (!isApiError(error, 'unauthenticated')) throw error;
  }
}

export async function enrollMfa(): Promise<MfaEnrollment> {
  return parse(mfaEnrollmentSchema, await apiWrite('post', '/auth/admin/mfa/enrolar'));
}

export async function confirmMfa(code: string): Promise<AuthUser> {
  return parse(authUserSchema, await apiWrite('post', '/auth/admin/mfa/confirmar', { codigo: code }));
}

export async function verifyMfa(code: string): Promise<AuthUser> {
  return parse(authUserSchema, await apiWrite('post', '/auth/admin/mfa/verificar', { codigo: code }));
}
