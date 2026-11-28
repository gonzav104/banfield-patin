import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';
import { profileStateSchema, type ProfileState } from './schemas';

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

/** Personal data of the profile as the backend takes it; a blank optional is simply left out. The DNI is only sent when the person typed one. */
export interface ProfileInput {
  nombre: string;
  apellido: string;
  dni?: string;
  telefono?: string;
  email?: string;
  parentesco?: string;
}

/** The account is identified by the session on the server: the client never sends a user, family or school id. */
export async function fetchProfile(): Promise<ProfileState> {
  return parse(profileStateSchema, await apiGet('/familia/mi-perfil'));
}

/** POST is never retried (apiWrite): a lost answer is checked by reading the profile again. */
export async function createProfile(input: ProfileInput): Promise<ProfileState> {
  return parse(profileStateSchema, await apiWrite('post', '/familia/mi-perfil', input));
}

export async function updateProfile(input: ProfileInput & { version: string }): Promise<ProfileState> {
  const { version, ...data } = input;
  return parse(profileStateSchema, await apiWrite('put', '/familia/mi-perfil', { ...data, versionEsperada: version }));
}

export async function claimPerson(input: { tutorId: string; version: string }): Promise<ProfileState> {
  return parse(profileStateSchema, await apiWrite('post', '/familia/mi-perfil/reclamar-tutor', { tutorId: input.tutorId, versionEsperada: input.version }));
}
