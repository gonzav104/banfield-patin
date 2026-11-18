import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet } from '@/lib/http';
import { athleteLinksSchema, familyAthletesSchema, type AthleteLinks, type LinkRow } from './schemas';

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

const mismatch = () => new ApiError('server', 'El servidor respondió con datos que no corresponden.');

/**
 * GET /api/admin/deportistas/{id}/vinculos: the revision of the links of the athlete and all its links (every state) from one snapshot. An
 * answer about another athlete, a row of another athlete, or a row whose revision is not the header's (not the same snapshot) is a malformed
 * answer and is never drawn. A 404 is uniform and is kept as it comes.
 */
export async function getAthleteLinks(athleteId: string): Promise<AthleteLinks> {
  const links = parse(athleteLinksSchema, await apiGet(`/admin/deportistas/${encodeURIComponent(athleteId)}/vinculos`));
  if (links.deportistaId !== athleteId) throw mismatch();
  if (links.vinculos.some((row) => row.deportistaId !== athleteId || row.versionVinculos !== links.versionVinculos)) throw mismatch();
  return links;
}

/** GET /api/admin/familias/{id}/deportistas: the links of ONE family, ordered by the server. A row of another family is a malformed answer, never drawn. */
export async function getFamilyAthletes(familyId: string): Promise<LinkRow[]> {
  const rows = parse(familyAthletesSchema, await apiGet(`/admin/familias/${encodeURIComponent(familyId)}/deportistas`));
  if (rows.some((row) => row.familiaId !== familyId)) throw mismatch();
  return rows;
}
