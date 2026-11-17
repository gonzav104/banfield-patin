import { z } from 'zod';
import { sportsStatusSchema } from '@/features/familia/schemas';
import { pageSchema } from '@/features/invitations/schemas';

/**
 * One row of the sports data of an athlete in a season (DeportistaTemporadaRespuesta, GET .../deportistas/{id}/temporadas[/{temporadaId}]).
 * `version` is the revision of THIS row, a decimal string within BIGINT: it is validated here so the unit that edits can send it back
 * as it is, and it is never shown. Free text has no official catalogue; the limits mirror the database columns (80, 40, 80 and 30).
 * Anything the server may add that is not listed here is dropped.
 */
export const sportsRowSchema = z.object({
  id: z.string(),
  deportistaId: z.string(),
  temporadaId: z.string(),
  anio: z.number().int().min(2020).max(2100),
  categoria: z.string().max(80).nullable().optional(),
  rama: z.string().max(40).nullable().optional(),
  divisional: z.string().max(80).nullable().optional(),
  numeroCorredor: z.string().max(30).nullable().optional(),
  estadoDeportivo: sportsStatusSchema,
  version: z.string().regex(/^[0-9]{1,19}$/),
});
export type SportsRow = z.infer<typeof sportsRowSchema>;

/** The history is an UNPAGED array (at most one row per year the school has a season for). */
export const sportsHistorySchema = z.array(sportsRowSchema);

/**
 * What the sports screens need of the athlete: the name and the state in the SCHOOL. The individual view of the athlete also carries
 * the full DNI and CUIL; this schema drops them so they never sit in the memory of these screens.
 */
export const athleteHeaderSchema = z.object({
  id: z.string(),
  nombre: z.string(),
  apellido: z.string(),
  activo: z.boolean(),
});
export type AthleteHeader = z.infer<typeof athleteHeaderSchema>;

/** GET /api/admin/temporadas (Pagina<TemporadaRespuesta>). Only the total matters here: whether the school has any season at all. */
export const seasonPageSchema = pageSchema(z.object({ id: z.string(), anio: z.number().int(), activa: z.boolean() }));
export type SeasonPage = z.infer<typeof seasonPageSchema>;
