import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';
import { familyAdminPageSchema, familyDetailSchema, tutorDetailSchema, type FamilyAdminPage, type FamilyDetail, type FamilyStatusFilter, type TutorDetail, type TutorInput, type TutorUpdateInput } from './schemas';

export const FAMILY_PAGE_SIZE = 20;
/** The backend cuts the search term at this length (Busqueda.LONGITUD_MAXIMA). */
export const SEARCH_MAX_LENGTH = 100;
/** FamiliaSolicitud.nombreReferencia: @Size(max = 150). */
export const NAME_MAX_LENGTH = 150;

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

export interface FamilyListParams {
  estado: FamilyStatusFilter;
  /** Already trimmed. Sent as a query parameter of the request only: never put it in a navigation URL. */
  busqueda: string;
  /** Zero-based, as the backend expects. */
  page: number;
}

export async function listFamilies({ estado, busqueda, page }: FamilyListParams): Promise<FamilyAdminPage> {
  const query = new URLSearchParams({ page: String(page), size: String(FAMILY_PAGE_SIZE) });
  if (estado !== 'TODOS') query.set('estado', estado);
  if (busqueda !== '') query.set('busqueda', busqueda);
  return parse(familyAdminPageSchema, await apiGet(`/admin/familias/listado?${query.toString()}`));
}

/** Ids are UUIDs; this lenient shape only keeps anything that could alter the path from ever being requested. */
const SAFE_ID = /^[A-Za-z0-9-]{1,64}$/;
export const isSafeFamilyId = (id: string | undefined): id is string => id !== undefined && SAFE_ID.test(id);

export async function getFamily(id: string): Promise<FamilyDetail> {
  return parse(familyDetailSchema, await apiGet(`/admin/familias/${encodeURIComponent(id)}`));
}

/** POST is never retried (apiWrite): a failed write is ambiguous and the caller must tell the user so. */
export async function createFamily(nombreReferencia: string): Promise<FamilyDetail> {
  return parse(familyDetailSchema, await apiWrite('post', '/admin/familias', { nombreReferencia }));
}

/**
 * PUT /api/admin/familias/{id}. `version` is the revision the administrator saw when starting the edit: it is sent as it
 * is, never refreshed on the way. Never retried (apiWrite): a failed write is ambiguous.
 */
export async function updateFamily(input: { id: string; nombreReferencia: string; version: string }): Promise<FamilyDetail> {
  return parse(
    familyDetailSchema,
    await apiWrite('put', `/admin/familias/${encodeURIComponent(input.id)}`, { nombreReferencia: input.nombreReferencia, versionEsperada: input.version }),
  );
}

export type FamilyStatusAction = 'activar' | 'desactivar';

/** POST /api/admin/familias/{id}/activar|desactivar with the revision seen when the confirmation was opened. */
export async function changeFamilyStatus(input: { id: string; action: FamilyStatusAction; version: string }): Promise<FamilyDetail> {
  return parse(
    familyDetailSchema,
    await apiWrite('post', `/admin/familias/${encodeURIComponent(input.id)}/${input.action}`, { versionEsperada: input.version }),
  );
}

/** Tutor ids have the same shape as family ids (UUIDs): the same guard keeps anything that could alter the path from being requested. */
export const isSafeTutorId = isSafeFamilyId;

/** GET /api/admin/tutores/{id}: the individual ADMIN view, with the FULL DNI. */
export async function getTutor(id: string): Promise<TutorDetail> {
  return parse(tutorDetailSchema, await apiGet(`/admin/tutores/${encodeURIComponent(id)}`));
}

/**
 * POST /api/admin/familias/{familiaId}/tutores. `version` is the family revision the administrator observed when the form was
 * opened (versionFamiliaEsperada): it is sent as it is, never refreshed on the way. Never retried (apiWrite): a failed write is
 * ambiguous, and creating a tutor does not move the family revision, so a blind resend could create a second one.
 */
export async function createTutor(input: { familyId: string; datos: TutorInput; version: string }): Promise<TutorDetail> {
  return parse(
    tutorDetailSchema,
    await apiWrite('post', `/admin/familias/${encodeURIComponent(input.familyId)}/tutores`, { ...input.datos, versionFamiliaEsperada: input.version }),
  );
}

/**
 * PUT /api/admin/tutores/{id}, a COMPLETE replacement. `version` is the tutor revision and `familyVersion` the family revision the
 * administrator observed when the edit started (versionEsperada / versionFamiliaEsperada): both are sent as they are, never
 * refreshed on the way. Never retried (apiWrite): a failed write is ambiguous.
 */
export async function updateTutor(input: { tutorId: string; datos: TutorUpdateInput; version: string; familyVersion: string }): Promise<TutorDetail> {
  return parse(
    tutorDetailSchema,
    await apiWrite('put', `/admin/tutores/${encodeURIComponent(input.tutorId)}`, {
      ...input.datos,
      versionEsperada: input.version,
      versionFamiliaEsperada: input.familyVersion,
    }),
  );
}
