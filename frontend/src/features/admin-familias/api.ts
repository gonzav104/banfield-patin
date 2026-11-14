import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet } from '@/lib/http';
import { familyAdminPageSchema, type FamilyAdminPage, type FamilyStatusFilter } from './schemas';

export const FAMILY_PAGE_SIZE = 20;
/** The backend cuts the search term at this length (Busqueda.LONGITUD_MAXIMA). */
export const SEARCH_MAX_LENGTH = 100;

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
