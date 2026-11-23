import type { z } from 'zod';
import { documentChecklistSchema, type DocumentChecklist } from '@/features/documentos-checklist/schemas';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiGetBlob, apiWrite } from '@/lib/http';
import {
  adminDocumentPageSchema, adminDocumentSchema,
  athleteDetailSchema, athletePageSchema,
  type AdminDocument,
  type AthleteDetail,
  type AthleteInput, type AthletePage, type AthleteStatusFilter, type AthleteUpdateInput } from './schemas';

export const ATHLETE_PAGE_SIZE = 20;
/** The most the backend returns in one page (Pagina.TAMANIO_MAXIMO): the verification of an uncertain creation asks for all of it. */
export const ATHLETE_VERIFY_PAGE_SIZE = 100;
/** The backend cuts the search term at this length (Busqueda.LONGITUD_MAXIMA). */
export const SEARCH_MAX_LENGTH = 100;

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

export interface AthleteListParams {
  estado: AthleteStatusFilter;
  /** Already trimmed. Sent as a query parameter of the request only: never put it in a navigation URL. */
  busqueda: string;
  /** Zero-based, as the backend expects. */
  page: number;
  /** Defaults to the list's page size. */
  size?: number;
}

/** The backend default is ACTIVOS, so `estado` always travels: what the screen shows is what is requested. */
export async function listAthletes({ estado, busqueda, page, size = ATHLETE_PAGE_SIZE }: AthleteListParams): Promise<AthletePage> {
  const query = new URLSearchParams({ estado, page: String(page), size: String(size) });
  if (busqueda !== '') query.set('busqueda', busqueda);
  return parse(athletePageSchema, await apiGet(`/admin/deportistas?${query.toString()}`));
}

/** Ids are UUIDs; this lenient shape only keeps anything that could alter the path from ever being requested. */
const SAFE_ID = /^[A-Za-z0-9-]{1,64}$/;
export const isSafeAthleteId = (id: string | undefined): id is string => id !== undefined && SAFE_ID.test(id);

/** GET /api/admin/deportistas/{id}: the individual ADMIN view, with the FULL DNI and CUIL. */
export async function getAthlete(id: string): Promise<AthleteDetail> {
  return parse(athleteDetailSchema, await apiGet(`/admin/deportistas/${encodeURIComponent(id)}`));
}

/**
 * POST /api/admin/deportistas. It carries no revision (a new athlete has none). Never retried (apiWrite): a failed write is
 * ambiguous, and the caller must tell the administrator so instead of resending.
 */
export async function createAthlete(datos: AthleteInput): Promise<AthleteDetail> {
  return parse(athleteDetailSchema, await apiWrite('post', '/admin/deportistas', datos));
}

/**
 * PUT /api/admin/deportistas/{id}, a COMPLETE replacement. `version` is the revision the administrator observed when the edit
 * started (versionEsperada, a decimal string): it is sent as it is, never refreshed on the way. Never retried (apiWrite): a failed
 * write is ambiguous.
 */
export async function updateAthlete(input: { id: string; datos: AthleteUpdateInput; version: string }): Promise<AthleteDetail> {
  return parse(athleteDetailSchema, await apiWrite('put', `/admin/deportistas/${encodeURIComponent(input.id)}`, { ...input.datos, versionEsperada: input.version }));
}

export type AthleteStatusAction = 'activar' | 'desactivar';

/** POST /api/admin/deportistas/{id}/activar|desactivar with the revision seen when the confirmation was opened. Never retried. */
export async function changeAthleteStatus(input: { id: string; action: AthleteStatusAction; version: string }): Promise<AthleteDetail> {
  return parse(
    athleteDetailSchema,
    await apiWrite('post', `/admin/deportistas/${encodeURIComponent(input.id)}/${input.action}`, { versionEsperada: input.version }),
  );
}

/** Every current document of an athlete fits in one page: there is at most one per type. */
export const ATHLETE_DOCUMENTS_PAGE_SIZE = 100;

/** Whose documents a screen manages: an athlete (default) or, since U19, a tutor. Same routes, other owner. */
export type DocumentOwnerKind = 'deportista' | 'tutor';
const ownerPath = (kind: DocumentOwnerKind, id: string) => `/admin/${kind === 'tutor' ? 'tutores' : 'deportistas'}/${encodeURIComponent(id)}`;

/** GET /api/admin/deportistas/{id}/checklist-documental (U20): the same PARTIAL checklist of the athlete's DNI the family sees. */
export async function fetchAdminDocumentChecklist(athleteId: string): Promise<DocumentChecklist> {
  return parse(documentChecklistSchema, await apiGet(`/admin/deportistas/${encodeURIComponent(athleteId)}/checklist-documental`));
}

/** GET /api/admin/{deportistas|tutores}/{id}/documentos: metadata of the CURRENT documents only (no file, no name, no MIME type). */
export async function listAthleteDocuments(athleteId: string, kind: DocumentOwnerKind = 'deportista'): Promise<AdminDocument[]> {
  const query = new URLSearchParams({ page: '0', size: String(ATHLETE_DOCUMENTS_PAGE_SIZE) });
  return parse(adminDocumentPageSchema, await apiGet(`${ownerPath(kind, athleteId)}/documentos?${query.toString()}`)).contenido;
}

export type DocumentReviewAction = 'APROBADO' | 'OBSERVADO';

export async function reviewAthleteDocument(
  athleteId: string,
  documentId: string,
  decision: { estado: DocumentReviewAction; versionEsperada: string; motivoObservacion?: string },
  beforeWrite: () => void,
  signal: AbortSignal,
  kind: DocumentOwnerKind = 'deportista',
): Promise<AdminDocument> {
  const path = `${ownerPath(kind, athleteId)}/documentos/${encodeURIComponent(documentId)}/revision`;
  const saved = parse(adminDocumentSchema, await apiWrite('post', path, decision, beforeWrite, { signal }));
  if (saved.id !== documentId || saved.version === undefined) {
    throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  }
  return saved;
}

/**
 * A document file, in memory only. The kind comes from the Content-Type of the answer, against an allow-list: anything else is
 * `unsupported` and its bytes are dropped (never rendered, never offered for download).
 */
export type DocumentFile = { kind: 'image'; blob: Blob } | { kind: 'pdf'; blob: Blob; fileName: string } | { kind: 'unsupported' };

const IMAGE_TYPES = new Set(['image/jpeg', 'image/png']);
export const FALLBACK_PDF_NAME = 'documento.pdf';
const SAFE_PDF_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\.pdf$/i;

/** The neutral name the server put in Content-Disposition, only when it is a plain «name.pdf»; otherwise `documento.pdf`. */
export function pdfFileName(contentDisposition: string | null): string {
  const match = contentDisposition ? /filename="([^"]*)"/i.exec(contentDisposition) ?? /filename=([^;\s]+)/i.exec(contentDisposition) : null;
  const name = match?.[1]?.trim() ?? '';
  return SAFE_PDF_NAME.test(name) ? name : FALLBACK_PDF_NAME;
}

/**
 * GET /api/admin/deportistas/{athleteId}/documentos/{documentId}/archivo, with the CSRF header the route requires. Every successful
 * answer is an AUDITED access on the server: the caller never retries it by itself. Not cached anywhere (no query cache).
 */
export async function fetchAthleteDocumentFile(athleteId: string, documentId: string, kind: DocumentOwnerKind = 'deportista'): Promise<DocumentFile> {
  const { blob, contentType, contentDisposition } = await apiGetBlob(
    `${ownerPath(kind, athleteId)}/documentos/${encodeURIComponent(documentId)}/archivo`,
  );
  if (contentType && IMAGE_TYPES.has(contentType)) return { kind: 'image', blob: blob.type === contentType ? blob : new Blob([blob], { type: contentType }) };
  if (contentType === 'application/pdf') {
    return { kind: 'pdf', blob: blob.type === contentType ? blob : new Blob([blob], { type: contentType }), fileName: pdfFileName(contentDisposition) };
  }
  return { kind: 'unsupported' };
}
