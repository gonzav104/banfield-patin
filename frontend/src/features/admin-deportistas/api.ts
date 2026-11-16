import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet } from '@/lib/http';
import { athleteDetailSchema, athletePageSchema, type AthleteDetail, type AthletePage, type AthleteStatusFilter } from './schemas';

export const ATHLETE_PAGE_SIZE = 20;
/** The backend cuts the search term at this length (Busqueda.LONGITUD_MAXIMA). */
export const SEARCH_MAX_LENGTH = 100;

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

export interface AthleteListParams {
  estado: AthleteStatusFilter;
  /** Already trimmed. Sent as a query parameter of the request only: never put it in a navigation URL. */
  busqueda: string;
  /** Zero-based, as the backend expects. */
  page: number;
}

/** The backend default is ACTIVOS, so `estado` always travels: what the screen shows is what is requested. */
export async function listAthletes({ estado, busqueda, page }: AthleteListParams): Promise<AthletePage> {
  const query = new URLSearchParams({ estado, page: String(page), size: String(ATHLETE_PAGE_SIZE) });
  if (busqueda !== '') query.set('busqueda', busqueda);
  return parse(athletePageSchema, await apiGet(`/admin/deportistas?${query.toString()}`));
}

/** Ids are UUIDs; this lenient shape only keeps anything that could alter the path from ever being requested. */
const SAFE_ID = /^[A-Za-z0-9-]{1,64}$/;
export const isSafeAthleteId = (id: string | undefined): id is string => id !== undefined && SAFE_ID.test(id);

/** GET /api/admin/deportistas/{id}: the individual ADMIN view, with the FULL DNI and CUIL. */
export async function getAthlete(id: string): Promise<AthleteDetail> {
  return parse(athleteDetailSchema, await apiGet(`/admin/deportistas/${encodeURIComponent(id)}`));
}
