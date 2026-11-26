import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';
import { documentChecklistSchema, type DocumentChecklist } from '@/features/documentos-checklist/schemas';
import type { PreparedDocument } from './image-normalization';
import {
  athleteDocumentPageSchema,
  athleteDocumentSchema,
  athleteFichaSchema,
  athletePageSchema,
  familyResponsablesSchema,
  myFamilySchema,
  uploadAvailabilitySchema,
  type AthleteDocument,
  type AthleteFicha,
  type AthletePage,
  type FamilyResponsable,
  type MyFamily,
  type UploadAvailability,
} from './schemas';

/** Page size of the athletes list and of the dashboard preview (the total comes from `totalElementos`). */
export const ATHLETES_PAGE_SIZE = 10;
export const ATHLETES_PREVIEW_SIZE = 3;

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

/** The family is identified by the session on the server: the client never sends a family id. */
export async function fetchMyFamily(): Promise<MyFamily> {
  return parse(myFamilySchema, await apiGet('/familia/mi-familia'));
}

export async function fetchAthletes(params: { page: number; size: number }): Promise<AthletePage> {
  const query = new URLSearchParams({ page: String(params.page), size: String(params.size) });
  return parse(athletePageSchema, await apiGet(`/familia/deportistas?${query.toString()}`));
}

/** 404 FAMILIA_NO_ENCONTRADA: the account has a family id but the family does not exist or is not active. */
export function isFamilyUnavailable(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404 && error.code === 'FAMILIA_NO_ENCONTRADA';
}

const ATHLETE_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** An athlete id in the URL is only used if it is a UUID: anything else never reaches the backend. */
export function isAthleteId(value: string | undefined): value is string {
  return value !== undefined && ATHLETE_ID.test(value);
}

/**
 * The minimal athlete sheet: ONE request (never the 15-field detail endpoint). The family comes from the session on the
 * server. An id that is not a UUID is refused here without any request.
 */
export async function fetchAthleteFicha(athleteId: string): Promise<AthleteFicha> {
  if (!isAthleteId(athleteId)) {
    throw new ApiError('validation', 'El identificador del deportista no es válido.', { status: 400, code: 'SOLICITUD_INVALIDA' });
  }
  return parse(athleteFichaSchema, await apiGet(`/familia/deportistas/${athleteId}/ficha`));
}

/**
 * The responsables vigentes of ONE own athlete: ONE request without parameters (the family comes from the session on the server). An id
 * that is not a UUID is refused here without any request.
 */
export async function fetchAthleteResponsables(athleteId: string): Promise<FamilyResponsable[]> {
  if (!isAthleteId(athleteId)) {
    throw new ApiError('validation', 'El identificador del deportista no es válido.', { status: 400, code: 'SOLICITUD_INVALIDA' });
  }
  return parse(familyResponsablesSchema, await apiGet(`/familia/deportistas/${athleteId}/responsables`)).responsables;
}

/**
 * 404 DEPORTISTA_NO_ENCONTRADO: one answer for a foreign, non-existent, revoked-link or other-school athlete. Anything
 * else with a 404 (for example a missing route) is NOT reported as "athlete not found".
 */
export function isAthleteNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404 && error.code === 'DEPORTISTA_NO_ENCONTRADO';
}

/** The two DNI sides of the athlete, the only types this screen uploads for an athlete. */
export const DNI_TYPES = ['DNI_DEPORTISTA_FRENTE', 'DNI_DEPORTISTA_DORSO'] as const;
/** The two DNI sides of a tutor (U19), the only types uploaded for a tutor. */
export const TUTOR_DNI_TYPES = ['DNI_TUTOR_FRENTE', 'DNI_TUTOR_DORSO'] as const;
export type DniType = (typeof DNI_TYPES)[number] | (typeof TUTOR_DNI_TYPES)[number];

/** Whose documents a screen manages: an athlete (default) or a tutor of the family (U19). */
export type DocumentOwnerKind = 'deportista' | 'tutor';
const ownerPath = (kind: DocumentOwnerKind, id: string) => `/familia/${kind === 'tutor' ? 'tutores' : 'deportistas'}/${id}`;
const ownerLabel = (kind: DocumentOwnerKind) => (kind === 'tutor' ? 'del tutor' : 'del deportista');

/** Current documents of one athlete or tutor (at most a handful: one page of 100 is enough). */
export async function fetchAthleteDocuments(ownerId: string, kind: DocumentOwnerKind = 'deportista'): Promise<AthleteDocument[]> {
  if (!isAthleteId(ownerId)) {
    throw new ApiError('validation', `El identificador ${ownerLabel(kind)} no es válido.`, { status: 400, code: 'SOLICITUD_INVALIDA' });
  }
  return parse(athleteDocumentPageSchema, await apiGet(`${ownerPath(kind, ownerId)}/documentos?page=0&size=100`)).contenido;
}

/** GET /api/familia/deportistas/{id}/checklist-documental (U20): the PARTIAL checklist of the athlete's DNI. */
export async function fetchDocumentChecklist(athleteId: string): Promise<DocumentChecklist> {
  return parse(documentChecklistSchema, await apiGet(`/familia/deportistas/${athleteId}/checklist-documental`));
}

export async function fetchUploadAvailability(): Promise<UploadAvailability> {
  return parse(uploadAvailabilitySchema, await apiGet('/familia/documentos/disponibilidad'));
}

/** A slow phone needs more than the default 15 s to send a few MB. A timeout is retried with the same key. */
const UPLOAD_TIMEOUT_MS = 120_000;

/**
 * Uploads ONE prepared document. The caller keeps `idempotencyKey` and `prepared` for a retry: the server returns the
 * same document (200) for the same key and bytes, and never stores it twice. Only `archivo` and `tipo` are sent, with a
 * neutral file name; the family, school and uploader come from the session on the server.
 */
export async function uploadAthleteDocument(input: {
  athleteId: string;
  tipo: DniType;
  prepared: PreparedDocument;
  idempotencyKey: string;
  kind?: DocumentOwnerKind;
  onProgress?: (fraction: number) => void;
}): Promise<AthleteDocument> {
  const kind = input.kind ?? 'deportista';
  if (!isAthleteId(input.athleteId)) {
    throw new ApiError('validation', `El identificador ${ownerLabel(kind)} no es válido.`, { status: 400, code: 'SOLICITUD_INVALIDA' });
  }
  const form = new FormData();
  form.append('tipo', input.tipo);
  form.append('archivo', input.prepared.blob, input.prepared.fileName);
  const data = await apiWrite<unknown>('post', `${ownerPath(kind, input.athleteId)}/documentos`, form, undefined, {
    headers: { 'Idempotency-Key': input.idempotencyKey },
    timeout: UPLOAD_TIMEOUT_MS,
    ...(input.onProgress ? { onUploadProgress: input.onProgress } : {}),
  });
  return parse(athleteDocumentSchema, data);
}

/**
 * Replaces an OBSERVED DNI of the athlete with a new file (U18). Same rules as `uploadAthleteDocument` (the caller keeps
 * `idempotencyKey` and `prepared` for a retry), but only `archivo` is sent: the type comes from the document it
 * replaces, which travels in the path and is authorized again by the server.
 */
export async function replaceAthleteDocument(input: {
  athleteId: string;
  documentId: string;
  prepared: PreparedDocument;
  idempotencyKey: string;
  kind?: DocumentOwnerKind;
  onProgress?: (fraction: number) => void;
}): Promise<AthleteDocument> {
  const kind = input.kind ?? 'deportista';
  if (!isAthleteId(input.athleteId) || !isAthleteId(input.documentId)) {
    throw new ApiError('validation', 'El identificador no es válido.', { status: 400, code: 'SOLICITUD_INVALIDA' });
  }
  const form = new FormData();
  form.append('archivo', input.prepared.blob, input.prepared.fileName);
  const data = await apiWrite<unknown>('post', `${ownerPath(kind, input.athleteId)}/documentos/${input.documentId}/reemplazo`, form, undefined, {
    headers: { 'Idempotency-Key': input.idempotencyKey },
    timeout: UPLOAD_TIMEOUT_MS,
    ...(input.onProgress ? { onUploadProgress: input.onProgress } : {}),
  });
  return parse(athleteDocumentSchema, data);
}
