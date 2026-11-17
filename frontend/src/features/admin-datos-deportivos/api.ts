import type { z } from 'zod';
import { isSafeAthleteId } from '@/features/admin-deportistas/api';
import { ApiError } from '@/lib/api-error';
import { apiGet } from '@/lib/http';
import { athleteHeaderSchema, seasonPageSchema, sportsHistorySchema, sportsRowSchema, type AthleteHeader, type SportsRow } from './schemas';

/** An id taken from the address is requested only if it cannot alter the path (same rule as the athlete screens). */
export const isSafeId = isSafeAthleteId;

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

/**
 * GET /api/admin/deportistas/{id}, read through a schema that keeps only the name and the state in the school: the response also
 * carries the full DNI and CUIL, which these screens never keep.
 */
export async function getAthleteHeader(id: string): Promise<AthleteHeader> {
  return parse(athleteHeaderSchema, await apiGet(`/admin/deportistas/${encodeURIComponent(id)}`));
}

/** GET /api/admin/deportistas/{id}/temporadas: unpaged, newest year first. A row of another athlete is a malformed answer, never drawn. */
export async function getSportsHistory(athleteId: string): Promise<SportsRow[]> {
  const rows = parse(sportsHistorySchema, await apiGet(`/admin/deportistas/${encodeURIComponent(athleteId)}/temporadas`));
  if (rows.some((row) => row.deportistaId !== athleteId)) throw new ApiError('server', 'El servidor respondió con datos que no corresponden.');
  return rows;
}

/** GET /api/admin/deportistas/{id}/temporadas/{temporadaId}: a row of another athlete or season is a malformed answer, never drawn. */
export async function getSportsRow(athleteId: string, seasonId: string): Promise<SportsRow> {
  const row = parse(sportsRowSchema, await apiGet(`/admin/deportistas/${encodeURIComponent(athleteId)}/temporadas/${encodeURIComponent(seasonId)}`));
  if (row.deportistaId !== athleteId || row.temporadaId !== seasonId) throw new ApiError('server', 'El servidor respondió con datos que no corresponden.');
  return row;
}

/**
 * GET /api/admin/temporadas, asked for ONE row of the first page: the answer that matters is `totalElementos`, the number of seasons
 * the school has. The listing is paged (at most 100 per page), so the length of a page is never taken as the total.
 */
export async function countSeasons(): Promise<number> {
  const page = parse(seasonPageSchema, await apiGet('/admin/temporadas?page=0&size=1'));
  return page.totalElementos;
}
