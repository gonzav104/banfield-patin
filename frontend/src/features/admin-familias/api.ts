import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';
import { familyAdminPageSchema, familyDetailSchema, type FamilyAdminPage, type FamilyDetail, type FamilyStatusFilter } from './schemas';

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
