import type { z } from 'zod';
import { isSafeId } from '@/features/admin-datos-deportivos/api';
import { seasonSchema, type Season } from '@/features/admin-datos-deportivos/schemas';
import { ApiError } from '@/lib/api-error';
import { apiWrite } from '@/lib/http';

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

/** An answer that does not describe what was asked is never trusted: the write may or may not have been applied. */
const mismatch = () => new ApiError('server', 'El servidor respondió con datos que no corresponden.');

function guardIdentity(stillCurrent: () => boolean) {
  if (!stillCurrent()) throw new ApiError('unauthenticated', 'La identidad de la sesión cambió.', { code: 'IDENTIDAD_CAMBIADA' });
}

/**
 * POST /api/admin/temporadas with `{anio}`. Never retried (apiWrite): a failed write is ambiguous. A 201 that is not a season of that year,
 * or that is already active (a new season is born inactive), is an answer that cannot be confirmed. `stillCurrent` is asked AFTER the CSRF
 * token was read and BEFORE the write, so a session that changed meanwhile never sends it.
 */
export async function createSeason(year: number, stillCurrent: () => boolean = () => true): Promise<Season> {
  const season = parse(seasonSchema, await apiWrite('post', '/admin/temporadas', { anio: year }, () => guardIdentity(stillCurrent)));
  if (season.anio !== year || season.activa) throw mismatch();
  return season;
}

export interface ActivateInput {
  seasonId: string;
  /** The id of the season observed as the active one when the administrator decided (null: none was active). Sent as it is, never refreshed. */
  expectedActiveId: string | null;
}

/**
 * POST /api/admin/temporadas/{id}/activar with the MANDATORY `{activaEsperadaId}` (FE-05/U13). It is the intent captured when the
 * confirmation was opened; the backend compares it with the confirmed active season under the school lock (409 EDICION_DESACTUALIZADA).
 * Never retried. A 200 for another season, or not active, is an answer that cannot be confirmed.
 */
export async function activateSeason(input: ActivateInput, stillCurrent: () => boolean = () => true): Promise<Season> {
  if (!isSafeId(input.seasonId)) throw new ApiError('validation', 'La temporada no es válida.');
  const path = `/admin/temporadas/${encodeURIComponent(input.seasonId)}/activar`;
  const season = parse(seasonSchema, await apiWrite('post', path, { activaEsperadaId: input.expectedActiveId }, () => guardIdentity(stillCurrent)));
  if (season.id !== input.seasonId || !season.activa) throw mismatch();
  return season;
}
