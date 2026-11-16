import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';
import { athleteDetailSchema, athletePageSchema, type AthleteDetail, type AthleteInput, type AthletePage, type AthleteStatusFilter } from './schemas';

export const ATHLETE_PAGE_SIZE = 20;
/** The most the backend returns in one page (Pagina.TAMANIO_MAXIMO): the verification of an uncertain creation asks for all of it. */
export const ATHLETE_VERIFY_PAGE_SIZE = 100;
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
  /** Defaults to the list's page size. */
  size?: number;
}

/** The backend default is ACTIVOS, so `estado` always travels: what the screen shows is what is requested. */
export async function listAthletes({ estado, busqueda, page, size = ATHLETE_PAGE_SIZE }: AthleteListParams): Promise<AthletePage> {
  const query = new URLSearchParams({ estado, page: String(page), size: String(size) });
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

/**
 * POST /api/admin/deportistas. It carries no revision (a new athlete has none). Never retried (apiWrite): a failed write is
 * ambiguous, and the caller must tell the administrator so instead of resending.
 */
export async function createAthlete(datos: AthleteInput): Promise<AthleteDetail> {
  return parse(athleteDetailSchema, await apiWrite('post', '/admin/deportistas', datos));
}
