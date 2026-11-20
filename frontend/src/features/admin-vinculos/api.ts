import { getFamily } from '@/features/admin-familias/api';
import type { AthleteSummary } from '@/features/admin-deportistas/schemas';
import { linkBatchResultSchema, type LinkBatchResult } from './schemas';
import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';
import { athleteLinksSchema, familyAthletesSchema, linkRowSchema, type AthleteLinks, type LinkRow } from './schemas';

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

export type LinkAction = 'revocar' | 'principal';
export type ChangeLinkInput = { athleteId: string; familyId: string; linkId: string; action: LinkAction; version: string; familyIds: string[] };

/** A confirmed response must belong to the requested pair AND represent the requested action. Malformed 2xx is uncertain. */
export async function changeLink(input: ChangeLinkInput): Promise<LinkRow> {
  const row = parse(linkRowSchema, await apiWrite('post',
    `/admin/familias/${encodeURIComponent(input.familyId)}/deportistas/${encodeURIComponent(input.athleteId)}/${input.action}`,
    { versionEsperada: input.version }));
  if (row.familiaId !== input.familyId || row.deportistaId !== input.athleteId || row.vinculoId !== input.linkId) throw mismatch();
  if (input.action === 'revocar' ? row.estado !== 'REVOCADO' || row.esPrincipal : row.estado !== 'ACTIVO' || !row.esPrincipal) throw mismatch();
  return row;
}

export type LinkBatchEntry = { athlete: AthleteSummary; versionEsperada: string; relation: LinkRow | null };
export type LinkBatchSnapshot = { familyId: string; entries: LinkBatchEntry[]; familyIds: string[] };
const validRevision = (value: string) => /^[0-9]{1,19}$/.test(value) && BigInt(value) <= 9223372036854775807n;
const validId = (value: string) => typeof value === 'string' && value.length > 0 && value.trim() === value;
function guardIdentity(stillCurrent: () => boolean) {
  if (!stillCurrent()) throw new ApiError('unauthenticated', 'La identidad de la sesión cambió.', { code: 'IDENTIDAD_CAMBIADA' });
}
function validateSelection(familyId: string, athletes: AthleteSummary[]) {
  if (!validId(familyId) || athletes.length < 1 || athletes.length > 50 || new Set(athletes.map((a) => a.id)).size !== athletes.length || athletes.some((a) => !validId(a.id))) {
    throw new ApiError('validation', 'Seleccioná entre uno y cincuenta deportistas diferentes.');
  }
  if (athletes.some((a) => !a.activo)) throw new ApiError('conflict', 'Hay deportistas inactivos.', { code: 'DEPORTISTA_INACTIVO' });
}

/** Copies only the paginated identification fields; never fetches a full athlete profile. */
export async function prepareFamilyLinks(familyId: string, athletes: AthleteSummary[], stillCurrent = () => true): Promise<LinkBatchSnapshot> {
  validateSelection(familyId, athletes);
  guardIdentity(stillCurrent);
  const family = await getFamily(familyId);
  guardIdentity(stillCurrent);
  if (family.id !== familyId) throw mismatch();
  if (!family.activa) throw new ApiError('conflict', 'La familia está inactiva.', { code: 'FAMILIA_INACTIVA' });
  const entries: LinkBatchEntry[] = [];
  const familyIds = new Set([familyId]);
  // Bounded sequential reads avoid a burst of fifty requests and stop on identity/resource changes.
  for (const athlete of athletes) {
    guardIdentity(stillCurrent);
    const observed = await getAthleteLinks(athlete.id);
    guardIdentity(stillCurrent);
    if (!validRevision(observed.versionVinculos)) throw mismatch();
    const matching = observed.vinculos.filter((row) => row.familiaId === familyId);
    if (matching.length > 1) throw mismatch();
    if (observed.vinculos.some((row) => !row.deportistaActivo)) throw new ApiError('conflict', 'El deportista está inactivo.', { code: 'DEPORTISTA_INACTIVO' });
    const relation = matching[0] ?? null;
    if (relation && relation.estado !== 'ACTIVO' && relation.estado !== 'REVOCADO') throw new ApiError('conflict', 'El vínculo no está disponible para esta operación.', { code: 'VINCULO_NO_DISPONIBLE' });
    observed.vinculos.forEach((row) => familyIds.add(row.familiaId));
    entries.push({ athlete: { id: athlete.id, nombre: athlete.nombre, apellido: athlete.apellido, dniEnmascarado: athlete.dniEnmascarado, activo: athlete.activo }, versionEsperada: observed.versionVinculos, relation });
  }
  return { familyId, entries, familyIds: [...familyIds] };
}

/** Captured revisions are sent unchanged; all per-athlete outcomes must be trustworthy before reporting any success. */
export async function linkFamilyAthletes(snapshot: LinkBatchSnapshot, stillCurrent = () => true): Promise<LinkBatchResult> {
  validateSelection(snapshot.familyId, snapshot.entries.map((e) => e.athlete));
  if (snapshot.entries.some((e) => !validRevision(e.versionEsperada))) throw new ApiError('validation', 'La revisión del vínculo no es válida.');
  guardIdentity(stillCurrent);
  const result = parse(linkBatchResultSchema, await apiWrite('post', `/admin/familias/${encodeURIComponent(snapshot.familyId)}/deportistas`, {
    deportistas: snapshot.entries.map((e) => ({ deportistaId: e.athlete.id, versionEsperada: e.versionEsperada })),
  }, () => guardIdentity(stillCurrent)));
  if (result.resultados.length !== snapshot.entries.length || new Set(result.resultados.map((r) => r.vinculo.vinculoId)).size !== result.resultados.length) throw mismatch();
  result.resultados.forEach(({ vinculo: row, resultado }, index) => {
    const entry = snapshot.entries[index]!;
    const expected = !entry.relation ? 'CREADO' : entry.relation.estado === 'REVOCADO' ? 'REACTIVADO' : 'SIN_CAMBIOS';
    const revision = BigInt(entry.versionEsperada) + (expected === 'SIN_CAMBIOS' ? 0n : 1n);
    if (resultado !== expected || row.familiaId !== snapshot.familyId || row.deportistaId !== entry.athlete.id || !validId(row.vinculoId) ||
      (entry.relation && row.vinculoId !== entry.relation.vinculoId) || row.estado !== 'ACTIVO' || !row.deportistaActivo ||
      !validRevision(row.versionVinculos) || BigInt(row.versionVinculos) !== revision) throw mismatch();
  });
  return result;
}
