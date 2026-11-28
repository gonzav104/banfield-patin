import { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';

export const PAGE_SIZE = 20;
/** The backend refuses a batch of more than 50 tutors. */
export const BATCH_MAX = 50;

export const verificationFilterSchema = z.enum(['DECLARADO', 'OBSERVADO']);
export type VerificationFilter = z.infer<typeof verificationFilterSchema>;

const id = z.string().regex(/^[A-Za-z0-9-]{1,64}$/);

/**
 * One row of GET /api/admin/tutores/por-verificar: what the family declared, ready to verify without retyping. ADMIN sees the full DNI
 * (never put it in a URL, a log or a storage). `dniFrente`/`dniDorso` are the state of the tutor's current DNI images (null = not
 * loaded); an approved image does NOT verify the identity.
 */
export const pendingTutorSchema = z.object({
  tutorId: id,
  familiaId: id,
  familiaNombre: z.string().nullable().optional(),
  nombre: z.string(),
  apellido: z.string(),
  dni: z.string().nullable().optional(),
  telefono: z.string().nullable().optional(),
  email: z.string().nullable().optional(),
  parentesco: z.string().nullable().optional(),
  estado: z.string(),
  motivoObservacion: z.string().nullable().optional(),
  origen: z.string().nullable().optional(),
  tieneCuenta: z.boolean(),
  cuentaConfirmada: z.boolean(),
  dniFrente: z.string().nullable().optional(),
  dniDorso: z.string().nullable().optional(),
  coincidenciasEnOtrasFamilias: z.number(),
  declaradoEn: z.string().nullable().optional(),
  version: z.string().regex(/^[0-9]{1,19}$/),
});
export type PendingTutor = z.infer<typeof pendingTutorSchema>;

const pageSchema = z.object({ contenido: z.array(pendingTutorSchema), pagina: z.number().optional(), tamanio: z.number().optional(), totalElementos: z.number() });
export type PendingTutorsPage = z.infer<typeof pageSchema>;

const decisionSchema = z.object({ tutorId: z.string(), estado: z.string(), motivoObservacion: z.string().nullable().optional(), version: z.string() });
const batchSchema = z.object({ resultados: z.array(z.object({ tutorId: z.string(), resultado: z.string(), version: z.string().nullable().optional() })) });
export type BatchResult = z.infer<typeof batchSchema>['resultados'][number];

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

/** The school comes from the session: the request only carries the filter and the page. */
export async function listPendingTutors({ estado, page }: { estado: VerificationFilter; page: number }): Promise<PendingTutorsPage> {
  const query = new URLSearchParams({ estado, page: String(page), size: String(PAGE_SIZE) });
  return parse(pageSchema, await apiGet(`/admin/tutores/por-verificar?${query.toString()}`));
}

export type Decision = { decision: 'VERIFICAR'; versionEsperada: string } | { decision: 'OBSERVAR'; motivo: string; versionEsperada: string };

export async function resolveTutor(tutorId: string, decision: Decision): Promise<void> {
  parse(decisionSchema, await apiWrite('post', `/admin/tutores/${encodeURIComponent(tutorId)}/verificacion`, decision));
}

/** Every tutor has its own outcome; the call itself only fails when the request is wrong as a whole. */
export async function verifyBatch(tutores: { tutorId: string; versionEsperada: string }[]): Promise<BatchResult[]> {
  return parse(batchSchema, await apiWrite('post', '/admin/tutores/verificacion-lote', { tutores })).resultados;
}

export const familyTutorPath = (tutor: PendingTutor) => `/admin/familias/${encodeURIComponent(tutor.familiaId)}/tutores/${encodeURIComponent(tutor.tutorId)}`;
