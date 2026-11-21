import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';
import type { PreparedDocument } from './image-normalization';
import {
  athleteDocumentPageSchema,
  athleteDocumentSchema,
  athleteFichaSchema,
  athletePageSchema,
  myFamilySchema,
  uploadAvailabilitySchema,
  type AthleteDocument,
  type AthleteFicha,
  type AthletePage,
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
 * 404 DEPORTISTA_NO_ENCONTRADO: one answer for a foreign, non-existent, revoked-link or other-school athlete. Anything
 * else with a 404 (for example a missing route) is NOT reported as "athlete not found".
 */
export function isAthleteNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404 && error.code === 'DEPORTISTA_NO_ENCONTRADO';
}

/** The two DNI sides of the athlete, the only types this screen uploads. */
export const DNI_TYPES = ['DNI_DEPORTISTA_FRENTE', 'DNI_DEPORTISTA_DORSO'] as const;
export type DniType = (typeof DNI_TYPES)[number];

/** Current documents of one athlete (at most a handful: one page of 100 is enough). */
export async function fetchAthleteDocuments(athleteId: string): Promise<AthleteDocument[]> {
  if (!isAthleteId(athleteId)) {
    throw new ApiError('validation', 'El identificador del deportista no es válido.', { status: 400, code: 'SOLICITUD_INVALIDA' });
  }
  return parse(athleteDocumentPageSchema, await apiGet(`/familia/deportistas/${athleteId}/documentos?page=0&size=100`)).contenido;
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
  onProgress?: (fraction: number) => void;
}): Promise<AthleteDocument> {
  if (!isAthleteId(input.athleteId)) {
    throw new ApiError('validation', 'El identificador del deportista no es válido.', { status: 400, code: 'SOLICITUD_INVALIDA' });
  }
  const form = new FormData();
  form.append('tipo', input.tipo);
  form.append('archivo', input.prepared.blob, input.prepared.fileName);
  const data = await apiWrite<unknown>('post', `/familia/deportistas/${input.athleteId}/documentos`, form, undefined, {
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
  onProgress?: (fraction: number) => void;
}): Promise<AthleteDocument> {
  if (!isAthleteId(input.athleteId) || !isAthleteId(input.documentId)) {
    throw new ApiError('validation', 'El identificador no es válido.', { status: 400, code: 'SOLICITUD_INVALIDA' });
  }
  const form = new FormData();
  form.append('archivo', input.prepared.blob, input.prepared.fileName);
  const data = await apiWrite<unknown>('post', `/familia/deportistas/${input.athleteId}/documentos/${input.documentId}/reemplazo`, form, undefined, {
    headers: { 'Idempotency-Key': input.idempotencyKey },
    timeout: UPLOAD_TIMEOUT_MS,
    ...(input.onProgress ? { onUploadProgress: input.onProgress } : {}),
  });
  return parse(athleteDocumentSchema, data);
}
