import type { z } from 'zod';
import { isSafeAthleteId } from '@/features/admin-deportistas/api';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';
import { athleteHeaderSchema, seasonPageSchema, sportsHistorySchema, sportsRowSchema, type AthleteHeader, type Season, type SportsRow } from './schemas';
import type { CreateSportsInput, UpdateSportsInput } from './sports-form';

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

/** The most the backend returns in one page of seasons (Pagina.TAMANIO_MAXIMO). */
export const SEASON_PAGE_SIZE = 100;
/** A school has at most 81 seasons (the year is limited to 2020-2100), so this is a guard against a server that never ends, not a limit. */
const MAX_SEASON_PAGES = 50;

/**
 * GET /api/admin/temporadas through EVERY page: the listing is paged, so the first page is not assumed to be all of it. It stops when
 * what was read reaches `totalElementos`; a page that comes back empty before that, or never reaching it, is a malformed answer.
 * The pages are read at different moments, so the result is accepted only as ONE coherent snapshot: the total must not change between
 * pages, no season may appear twice and at most one may be active (the backend guarantees it at any single moment). Anything else is a
 * malformed answer, never drawn: an administrator must not decide, nor build an activation intent, from a mix of two states.
 */
export async function listAllSeasons(): Promise<Season[]> {
  const seasons: Season[] = [];
  let total: number | null = null;
  for (let page = 0; page < MAX_SEASON_PAGES; page += 1) {
    const result = parse(seasonPageSchema, await apiGet(`/admin/temporadas?page=${page}&size=${SEASON_PAGE_SIZE}`));
    if (total !== null && result.totalElementos !== total) throw incoherentSeasons();
    total = result.totalElementos;
    seasons.push(...result.contenido);
    if (seasons.length >= result.totalElementos) {
      if (seasons.length !== result.totalElementos || new Set(seasons.map((s) => s.id)).size !== seasons.length || seasons.filter((s) => s.activa).length > 1) throw incoherentSeasons();
      return seasons;
    }
    if (result.contenido.length === 0) break;
  }
  throw new ApiError('server', 'El servidor respondió con un listado de temporadas incompleto.');
}

const incoherentSeasons = () => new ApiError('server', 'El servidor respondió con un listado de temporadas incoherente.');

/**
 * POST /api/admin/deportistas/{id}/temporadas. It carries no revision (a new row has none). Never retried (apiWrite): a failed write
 * is ambiguous and the caller must tell the administrator so instead of resending. A 201 whose body is unreadable or belongs to
 * another athlete or season is treated as an answer that cannot be confirmed.
 */
export async function createSportsRow(input: { athleteId: string; datos: CreateSportsInput }): Promise<SportsRow> {
  const row = parse(sportsRowSchema, await apiWrite('post', `/admin/deportistas/${encodeURIComponent(input.athleteId)}/temporadas`, input.datos));
  if (row.deportistaId !== input.athleteId || row.temporadaId !== input.datos.temporadaId) throw new ApiError('server', 'El servidor respondió con datos que no corresponden.');
  return row;
}

/**
 * PUT /api/admin/deportistas/{id}/temporadas/{temporadaId}: a COMPLETE replacement protected by the revision of THAT row. `version` is the
 * revision the administrator observed (versionEsperada, a decimal string): it is sent as it is, never refreshed on the way. Never retried
 * (apiWrite): a failed write is ambiguous. A 200 whose body is unreadable or belongs to another athlete or season is treated as an
 * answer that cannot be confirmed.
 */
export async function updateSportsRow(input: { athleteId: string; seasonId: string; datos: UpdateSportsInput; version: string }): Promise<SportsRow> {
  const path = `/admin/deportistas/${encodeURIComponent(input.athleteId)}/temporadas/${encodeURIComponent(input.seasonId)}`;
  const row = parse(sportsRowSchema, await apiWrite('put', path, { ...input.datos, versionEsperada: input.version }));
  if (row.deportistaId !== input.athleteId || row.temporadaId !== input.seasonId) throw new ApiError('server', 'El servidor respondió con datos que no corresponden.');
  return row;
}
