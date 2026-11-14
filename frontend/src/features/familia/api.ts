import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet } from '@/lib/http';
import { athleteFichaSchema, athletePageSchema, myFamilySchema, type AthleteFicha, type AthletePage, type MyFamily } from './schemas';

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

const ATHLETE_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** An athlete id in the URL is only used if it is a UUID: anything else never reaches the backend. */
export function isAthleteId(value: string | undefined): value is string {
  return value !== undefined && ATHLETE_ID.test(value);
}

/**
 * The minimal athlete sheet: ONE request (never the 15-field detail endpoint). The family comes from the session on the
 * server. An id that is not a UUID is refused here without any request.
 */
export async function fetchAthleteFicha(athleteId: string): Promise<AthleteFicha> {
  if (!isAthleteId(athleteId)) {
    throw new ApiError('validation', 'El identificador del deportista no es válido.', { status: 400, code: 'SOLICITUD_INVALIDA' });
  }
  return parse(athleteFichaSchema, await apiGet(`/familia/deportistas/${athleteId}/ficha`));
}

/**
 * 404 DEPORTISTA_NO_ENCONTRADO: one answer for a foreign, non-existent, revoked-link or other-school athlete. Anything
 * else with a 404 (for example a missing route) is NOT reported as "athlete not found".
 */
export function isAthleteNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404 && error.code === 'DEPORTISTA_NO_ENCONTRADO';
}
