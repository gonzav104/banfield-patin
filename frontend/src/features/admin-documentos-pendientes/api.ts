import { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet } from '@/lib/http';

export const PENDING_PAGE_SIZE = 20;

export const pendingOwnerFilterSchema = z.enum(['TODOS', 'DEPORTISTA', 'TUTOR']);
export type PendingOwnerFilter = z.infer<typeof pendingOwnerFilterSchema>;

/**
 * One row of GET /api/admin/documentos/pendientes: it identifies the document and its owner, nothing else (no file, no name, no
 * storage key, no hash, no review version: the review reads its own version in the ficha). `familiaId` is the tutor's family and is
 * what the tutor's ficha needs in its address.
 */
export const pendingDocumentSchema = z.object({
  id: z.string().regex(/^[A-Za-z0-9-]{1,64}$/),
  tipo: z.string(),
  creadoEn: z.string(),
  titularTipo: z.enum(['DEPORTISTA', 'TUTOR']),
  titularId: z.string().regex(/^[A-Za-z0-9-]{1,64}$/),
  titularNombre: z.string(),
  familiaId: z.string().regex(/^[A-Za-z0-9-]{1,64}$/).nullable().optional(),
  reemplazo: z.boolean(),
});
export type PendingDocument = z.infer<typeof pendingDocumentSchema>;

const pendingPageSchema = z.object({ contenido: z.array(pendingDocumentSchema), totalElementos: z.number() });
export type PendingPage = z.infer<typeof pendingPageSchema>;

export interface PendingParams {
  titular: PendingOwnerFilter;
  page: number;
}

/** The school comes from the session: the request carries no scope parameter, only the optional owner filter and the page. */
export async function listPendingDocuments({ titular, page }: PendingParams): Promise<PendingPage> {
  const query = new URLSearchParams({ page: String(page), size: String(PENDING_PAGE_SIZE) });
  if (titular !== 'TODOS') query.set('titular', titular);
  const result = pendingPageSchema.safeParse(await apiGet(`/admin/documentos/pendientes?${query.toString()}`));
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

/** Where the review happens: the ficha of the athlete or of the tutor (null when the owner cannot be addressed safely). */
export function fichaPath(document: PendingDocument): string | null {
  if (document.titularTipo === 'DEPORTISTA') return `/admin/deportistas/${encodeURIComponent(document.titularId)}`;
  return document.familiaId ? `/admin/familias/${encodeURIComponent(document.familiaId)}/tutores/${encodeURIComponent(document.titularId)}` : null;
}
