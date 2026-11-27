import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';
import type { PreparedDocument } from '@/features/familia/image-normalization';
import { athleteDocumentSchema, type AthleteDocument } from '@/features/familia/schemas';
import type { AptoData } from './form';
import { aptoDocumentSchema, aptoSchema, familySeasonsSchema, type Apto, type AptoDocument, type SeasonOption } from './schemas';

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

const ID = /^[A-Za-z0-9-]{1,64}$/;
const invalidId = () => new ApiError('validation', 'El identificador no es válido.', { status: 400, code: 'SOLICITUD_INVALIDA' });
const requireIds = (...ids: string[]) => {
  if (!ids.every((id) => ID.test(id))) throw invalidId();
};

/** A slow phone needs more than the default 15 s to send a few MB. A timeout is retried with the same key. */
const UPLOAD_TIMEOUT_MS = 120_000;

/** GET /api/familia/deportistas/{id}/temporadas: the seasons the athlete has data in; the only ones the family can pick. */
export async function fetchFamilySeasons(athleteId: string): Promise<SeasonOption[]> {
  requireIds(athleteId);
  const rows = parse(familySeasonsSchema, await apiGet(`/familia/deportistas/${encodeURIComponent(athleteId)}/temporadas`));
  return [...rows].sort((a, b) => b.anio - a.anio).map((row) => ({ id: row.temporadaId, anio: row.anio, activa: row.temporadaActiva }));
}

const aptoPath = (area: 'familia' | 'admin', athleteId: string, seasonId: string) =>
  `/${area}/deportistas/${encodeURIComponent(athleteId)}/temporadas/${encodeURIComponent(seasonId)}/apto-fisico`;

/** The family's view: the data and the review reason only when this family uploaded the current version (`datosVisibles`). */
export async function fetchFamilyApto(athleteId: string, seasonId: string): Promise<Apto> {
  requireIds(athleteId, seasonId);
  return parse(aptoSchema, await apiGet(aptoPath('familia', athleteId, seasonId)));
}

/** The administrator's view: always the data, and the `revision` that the correction needs. */
export async function fetchAdminApto(athleteId: string, seasonId: string): Promise<Apto> {
  requireIds(athleteId, seasonId);
  return parse(aptoSchema, await apiGet(aptoPath('admin', athleteId, seasonId)));
}

function aptoForm(prepared: PreparedDocument, data: AptoData): FormData {
  const form = new FormData();
  form.append('fechaEmision', data.fechaEmision);
  form.append('medicoNombre', data.medicoNombre);
  form.append('medicoMatricula', data.medicoMatricula);
  form.append('archivo', prepared.blob, prepared.fileName);
  return form;
}

interface TransferInput {
  athleteId: string;
  prepared: PreparedDocument;
  data: AptoData;
  idempotencyKey: string;
  onProgress?: (fraction: number) => void;
}

const transferOptions = (input: TransferInput) => ({
  headers: { 'Idempotency-Key': input.idempotencyKey },
  timeout: UPLOAD_TIMEOUT_MS,
  ...(input.onProgress ? { onUploadProgress: input.onProgress } : {}),
});

/**
 * Uploads the apto of one season. The caller keeps `idempotencyKey`, `prepared` AND `data` for a retry: the server includes the
 * three data and the season in the identity of the operation, so the same key with other data is a definitive conflict.
 * `tipo` and `temporadaId` are sent (the family, school and uploader come from the session). Never retried by itself.
 */
export async function uploadApto(input: TransferInput & { seasonId: string }): Promise<AthleteDocument> {
  requireIds(input.athleteId, input.seasonId);
  const form = aptoForm(input.prepared, input.data);
  form.append('tipo', 'APTO_FISICO');
  form.append('temporadaId', input.seasonId);
  const data = await apiWrite<unknown>('post', `/familia/deportistas/${encodeURIComponent(input.athleteId)}/documentos`, form, undefined, transferOptions(input));
  return parse(athleteDocumentSchema, data);
}

/** Replaces an OBSERVED apto: the type and season come from the document it replaces; the new version brings its own data. */
export async function replaceApto(input: TransferInput & { documentId: string }): Promise<AthleteDocument> {
  requireIds(input.athleteId, input.documentId);
  const form = aptoForm(input.prepared, input.data);
  const path = `/familia/deportistas/${encodeURIComponent(input.athleteId)}/documentos/${encodeURIComponent(input.documentId)}/reemplazo`;
  return parse(athleteDocumentSchema, await apiWrite<unknown>('post', path, form, undefined, transferOptions(input)));
}

/**
 * ADMIN correction of the three data (PUT …/datos-apto) with the optimistic `versionEsperada` (a decimal string). A change leaves the
 * document in review: the approval is withdrawn by the server. The answer is the document with its new `revision`.
 */
export async function correctApto(
  athleteId: string,
  documentId: string,
  input: { versionEsperada: string; data: AptoData },
  beforeWrite: () => void,
  signal: AbortSignal,
): Promise<AptoDocument> {
  requireIds(athleteId, documentId);
  const body = { versionEsperada: input.versionEsperada, ...input.data };
  const path = `/admin/deportistas/${encodeURIComponent(athleteId)}/documentos/${encodeURIComponent(documentId)}/datos-apto`;
  const saved = parse(aptoDocumentSchema, await apiWrite('put', path, body, beforeWrite, { signal }));
  if (saved.id !== documentId) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return saved;
}
