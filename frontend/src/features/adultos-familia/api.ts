import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';
import { adultSchema, adultsStateSchema, type Adult, type AdultsState } from './schemas';

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

/** Personal data of an adult as the backend takes it; a blank optional is left out. The DNI is only sent when the person typed one. */
export interface AdultInput {
  nombre: string;
  apellido: string;
  dni?: string;
  telefono?: string;
  email?: string;
  parentesco?: string;
}

/** The family comes from the session: the client never sends a user, family or school id. */
export async function fetchAdults(): Promise<AdultsState> {
  return parse(adultsStateSchema, await apiGet('/familia/tutores'));
}

/**
 * POST is never retried by the client (apiWrite). The same `Idempotency-Key` with the same data makes a repeated request return the
 * adult already created instead of a duplicate; the caller keeps the key while the outcome is uncertain.
 */
export async function createAdult(input: AdultInput, key: string): Promise<Adult> {
  return parse(adultSchema, await apiWrite('post', '/familia/tutores', input, undefined, { headers: { 'Idempotency-Key': key } }));
}

export async function updateAdult(id: string, input: AdultInput & { version: string }): Promise<Adult> {
  const { version, ...data } = input;
  return parse(adultSchema, await apiWrite('put', `/familia/tutores/${encodeURIComponent(id)}`, { ...data, versionEsperada: version }));
}
