import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet } from '@/lib/http';
import { responsablesPageSchema, type ResponsablesPage } from './schemas';

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
export async function getResponsables(athleteId: string, historial: boolean, page: number): Promise<ResponsablesPage> {
  const params = new URLSearchParams({ historial: String(historial), page: String(page), size: String(RESPONSABLES_PAGE_SIZE) });
  const result = parse(responsablesPageSchema, await apiGet(`/admin/deportistas/${encodeURIComponent(athleteId)}/vinculos-tutores?${params.toString()}`));
  const inconsistent = result.contenido.some((r) => (r.cerradoEn === null) !== (r.causaCierre === null) || (!historial && r.cerradoEn !== null));
  if (inconsistent) throw new ApiError('server', 'El servidor respondió con datos que no corresponden.');
  return result;
}
