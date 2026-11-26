import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';
import { getAthlete } from '@/features/admin-deportistas/api';
import { getFamily, getTutor } from '@/features/admin-familias/api';
import { getAthleteLinks } from '@/features/admin-vinculos/api';
import type { ResponsableTipo } from '@/features/responsables/tipo';
import { responsableSchema, responsablesPageSchema, type Responsable, type ResponsablesPage } from './schemas';

export const RESPONSABLES_PAGE_SIZE = 20;

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

/**
 * GET /api/admin/deportistas/{id}/vinculos-tutores: open responsibilities by default, `historial=true` adds the closed ones. A closed row in
 * an open-only answer, or an open row marked as closed (cause without date), is a malformed answer and is never drawn.
 */
export async function getResponsables(athleteId: string, historial: boolean, page: number, size = RESPONSABLES_PAGE_SIZE): Promise<ResponsablesPage> {
  const params = new URLSearchParams({ historial: String(historial), page: String(page), size: String(size) });
  const result = parse(responsablesPageSchema, await apiGet(`/admin/deportistas/${encodeURIComponent(athleteId)}/vinculos-tutores?${params.toString()}`));
  const inconsistent = result.contenido.some((r) => (r.cerradoEn === null) !== (r.causaCierre === null) || (!historial && r.cerradoEn !== null));
  if (inconsistent) throw new ApiError('server', 'El servidor respondió con datos que no corresponden.');
  return result;
}

const mismatch = () => new ApiError('server', 'El servidor respondió con datos que no corresponden.');
const validRevision = (value: string) => /^[0-9]{1,19}$/.test(value) && BigInt(value) <= 9223372036854775807n;
const ownBase = (athleteId: string) => `/admin/deportistas/${encodeURIComponent(athleteId)}/vinculos-tutores`;

/** The server clamps the size at 100; every page is read, never a partial list presented as complete. */
const OPEN_PAGE_SIZE = 100;
const MAX_PAGES = 50;
export async function listOpenResponsables(athleteId: string): Promise<Responsable[]> {
  const all: Responsable[] = [];
  for (let page = 0; page < MAX_PAGES; page += 1) {
    const result = await getResponsables(athleteId, false, page, OPEN_PAGE_SIZE);
    all.push(...result.contenido);
    if (all.length >= result.totalElementos || result.contenido.length === 0) return all;
  }
  throw mismatch();
}

/** A tutor that can be proposed as responsible: active, of an active family with an ACTIVE link to the athlete, and without an open responsibility. */
export interface Candidate {
  tutorId: string;
  nombre: string;
  apellido: string;
  parentesco: string | null;
  dniEnmascarado: string | null;
  familiaId: string;
  familiaNombre: string;
}

/**
 * Eligible tutors of ONE athlete, from reads that already exist: the links of the athlete (ACTIVE families), each of those families once
 * (its tutors, DNI already masked) and every page of the open responsibilities (to exclude who already is one). Nothing is requested for a
 * family without an ACTIVE link, and no full tutor record is fetched here: the revisions are read only for the one chosen (`observeCandidate`).
 */
export async function getCandidates(athleteId: string): Promise<Candidate[]> {
  const links = await getAthleteLinks(athleteId);
  const families = new Map<string, string>();
  for (const row of links.vinculos) if (row.estado === 'ACTIVO') families.set(row.familiaId, row.familiaNombre);
  const open = new Set((await listOpenResponsables(athleteId)).map((r) => r.tutorId));
  const candidates: Candidate[] = [];
  for (const [familiaId, familiaNombre] of families) {
    const family = await getFamily(familiaId);
    if (family.id !== familiaId) throw mismatch();
    if (!family.activa) continue;
    for (const tutor of family.tutores) {
      if (!tutor.activo || open.has(tutor.id)) continue;
      candidates.push({ tutorId: tutor.id, nombre: tutor.nombre, apellido: tutor.apellido, parentesco: tutor.parentesco ?? null,
        dniEnmascarado: tutor.dniEnmascarado ?? null, familiaId, familiaNombre });
    }
  }
  return candidates.sort((a, b) => a.apellido.localeCompare(b.apellido, 'es') || a.nombre.localeCompare(b.nombre, 'es') || a.tutorId.localeCompare(b.tutorId));
}

/** The four revisions the alta must carry, observed together just before the confirmation and never invented. */
export interface Observation {
  candidate: Candidate;
  athleteId: string;
  versionFamilia: string;
  versionDeportista: string;
  versionTutor: string;
  versionVinculos: string;
}

const refused = (code: string, message: string) => new ApiError('conflict', message, { status: 409, code });

/**
 * Chosen candidate -> fresh revisions and states. It re-reads the tutor, the family, the athlete, its links and the open responsibilities
 * together, and refuses (with the same code the server would use) anything that is no longer true, so a confirmation is never offered
 * for a stale choice. Each revision is validated as a decimal string within BIGINT.
 */
export async function observeCandidate(athleteId: string, candidate: Candidate): Promise<Observation> {
  const [tutor, family, athlete, links, open] = await Promise.all([
    getTutor(candidate.tutorId), getFamily(candidate.familiaId), getAthlete(athleteId), getAthleteLinks(athleteId), listOpenResponsables(athleteId),
  ]);
  if (tutor.id !== candidate.tutorId || tutor.familiaId !== candidate.familiaId || family.id !== candidate.familiaId || athlete.id !== athleteId) throw mismatch();
  if (!athlete.activo) throw refused('DEPORTISTA_INACTIVO', 'El deportista está inactivo.');
  if (!family.activa) throw refused('FAMILIA_INACTIVA', 'La familia está inactiva.');
  if (!tutor.activo) throw refused('ESTADO_INCOMPATIBLE', 'El tutor está inactivo.');
  if (!links.vinculos.some((row) => row.familiaId === candidate.familiaId && row.estado === 'ACTIVO')) throw refused('ESTADO_INCOMPATIBLE', 'El vínculo de la familia no está activo.');
  if (open.some((r) => r.tutorId === candidate.tutorId)) throw refused('RESPONSABLE_YA_VIGENTE', 'El tutor ya es responsable vigente.');
  const observation: Observation = { candidate, athleteId, versionFamilia: family.version, versionDeportista: athlete.version, versionTutor: tutor.version, versionVinculos: links.versionVinculos };
  if (![observation.versionFamilia, observation.versionDeportista, observation.versionTutor, observation.versionVinculos].every(validRevision)) throw mismatch();
  return observation;
}

export interface CreateInput {
  observation: Observation;
  tipo: ResponsableTipo | null;
  /** One `Idempotency-Key` per confirmation: kept for every retry of the same request, new only for a new confirmation. */
  key: string;
}

/**
 * POST .../vinculos-tutores with the observed revisions and the key. A 2xx for another tutor, or with another declared type, is not a
 * confirmation. A reply to a REPEATED request may be a responsibility that was closed later: it is returned as is (the caller tells it apart).
 */
export async function createResponsable(input: CreateInput): Promise<Responsable> {
  const { observation, tipo, key } = input;
  const row = parse(responsableSchema, await apiWrite('post', ownBase(observation.athleteId), {
    tutorId: observation.candidate.tutorId,
    ...(tipo === null ? {} : { tipo }),
    versionFamilia: observation.versionFamilia,
    versionDeportista: observation.versionDeportista,
    versionTutor: observation.versionTutor,
    versionVinculos: observation.versionVinculos,
  }, undefined, { headers: { 'Idempotency-Key': key } }));
  if (row.tutorId !== observation.candidate.tutorId || row.familiaId !== observation.candidate.familiaId || row.tipo !== tipo) throw mismatch();
  return row;
}

export interface UpdateTipoInput { athleteId: string; id: string; version: string; tipo: ResponsableTipo | null }

/** PUT .../{id}: the only editable datum. The answer must be the same responsibility with the requested type. */
export async function updateResponsableTipo(input: UpdateTipoInput): Promise<Responsable> {
  const row = parse(responsableSchema, await apiWrite('put', `${ownBase(input.athleteId)}/${encodeURIComponent(input.id)}`, { tipo: input.tipo, versionEsperada: input.version }));
  if (row.id !== input.id || row.tipo !== input.tipo || row.cerradoEn !== null) throw mismatch();
  return row;
}

export interface RevokeInput { athleteId: string; id: string; version: string }

/** POST .../{id}/revocar: irreversible. The answer must be the same responsibility, closed by the administrator. */
export async function revokeResponsable(input: RevokeInput): Promise<Responsable> {
  const row = parse(responsableSchema, await apiWrite('post', `${ownBase(input.athleteId)}/${encodeURIComponent(input.id)}/revocar`, { versionEsperada: input.version }));
  if (row.id !== input.id || row.cerradoEn === null || row.causaCierre !== 'REVOCACION_ADMIN') throw mismatch();
  return row;
}

