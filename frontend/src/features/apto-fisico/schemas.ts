import { z } from 'zod';
import { documentStatusSchema } from '@/features/familia/schemas';

/** Derived state of the apto in one season (computed by the backend on every read; never stored). */
export const aptoStateSchema = z.enum(['FALTA', 'EN_REVISION', 'OBSERVADO', 'CUMPLIDO', 'VENCIDO']);
export type AptoState = z.infer<typeof aptoStateSchema>;

const idSchema = z.string().regex(/^[A-Za-z0-9-]{1,64}$/);
const dateSchema = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);

/**
 * The current version of the apto as `GET …/temporadas/{id}/apto-fisico` reports it. The three declared data and the review
 * reason belong to the family that uploaded this version: for any other family the server sends `datosVisibles: false` and
 * nulls. This schema enforces that on its own, so a hidden value can never reach a component even if a server (or a test double)
 * sent it. `revision` only exists for ADMIN. Unknown fields are dropped (the server never sends a file, a key or a hash here).
 */
export const aptoDocumentSchema = z
  .object({
    id: idSchema,
    estado: documentStatusSchema,
    fechaEmision: dateSchema.nullable().optional(),
    medicoNombre: z.string().max(150).nullable().optional(),
    medicoMatricula: z.string().max(50).nullable().optional(),
    datosVisibles: z.boolean(),
    motivoObservacion: z.string().max(500).nullable().optional(),
    cargadoEn: z.string(),
    revision: z.number().int().nonnegative().nullable().optional(),
  })
  .transform((doc) => ({
    id: doc.id,
    estado: doc.estado,
    datosVisibles: doc.datosVisibles,
    fechaEmision: doc.datosVisibles ? (doc.fechaEmision ?? null) : null,
    medicoNombre: doc.datosVisibles ? (doc.medicoNombre ?? null) : null,
    medicoMatricula: doc.datosVisibles ? (doc.medicoMatricula ?? null) : null,
    motivoObservacion: doc.datosVisibles && doc.estado === 'OBSERVADO' ? (doc.motivoObservacion ?? null) : null,
    cargadoEn: doc.cargadoEn,
    revision: doc.revision ?? null,
  }));
export type AptoDocument = z.infer<typeof aptoDocumentSchema>;

export const aptoSchema = z.object({
  deportistaId: idSchema,
  temporadaId: idSchema,
  anio: z.number().int().min(2020).max(2100),
  estado: aptoStateSchema,
  bloqueante: z.boolean(),
  vigencia: z.object({ desde: dateSchema, hasta: dateSchema, confirmada: z.boolean() }),
  avisos: z.array(z.string()),
  documento: aptoDocumentSchema.nullable().optional(),
});
export type Apto = z.infer<typeof aptoSchema>;

/** One season of the athlete as the family portal lists it (`GET /api/familia/deportistas/{id}/temporadas`). */
export const familySeasonSchema = z.object({ temporadaId: idSchema, anio: z.number().int().min(2020).max(2100), temporadaActiva: z.boolean() });
export const familySeasonsSchema = z.array(familySeasonSchema);
export type SeasonOption = { id: string; anio: number; activa: boolean };
