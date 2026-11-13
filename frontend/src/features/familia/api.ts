import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet } from '@/lib/http';
import { athletePageSchema, myFamilySchema, type AthletePage, type MyFamily } from './schemas';

/** Page size of the athletes list and of the dashboard preview (the total comes from `totalElementos`). */
export const ATHLETES_PAGE_SIZE = 10;
export const ATHLETES_PREVIEW_SIZE = 3;

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

/** The family is identified by the session on the server: the client never sends a family id. */
export async function fetchMyFamily(): Promise<MyFamily> {
  return parse(myFamilySchema, await apiGet('/familia/mi-familia'));
}

export async function fetchAthletes(params: { page: number; size: number }): Promise<AthletePage> {
  const query = new URLSearchParams({ page: String(params.page), size: String(params.size) });
  return parse(athletePageSchema, await apiGet(`/familia/deportistas?${query.toString()}`));
}

/** 404 FAMILIA_NO_ENCONTRADA: the account has a family id but the family does not exist or is not active. */
export function isFamilyUnavailable(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404 && error.code === 'FAMILIA_NO_ENCONTRADA';
}
