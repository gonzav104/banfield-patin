import { z } from 'zod';

/**
 * The state of the RELATION between a family and an athlete (EstadoVinculo). The API only produces ACTIVO and REVOCADO, but the database also
 * holds PENDIENTE and RECHAZADO. A closed enum: an unknown value is a malformed answer, never drawn as if it were a known state.
 */
export const linkStateSchema = z.enum(['PENDIENTE', 'ACTIVO', 'RECHAZADO', 'REVOCADO']);
export type LinkState = z.infer<typeof linkStateSchema>;

/**
 * One link as the ADMIN sees it (VinculoRespuesta). Only what the screens draw plus `versionVinculos` and `vinculoId` (the list key).
 * `versionVinculos` is the revision of the links of THAT row's athlete, a decimal string within BIGINT: it is validated here and kept inside
 * the private query model, and it is never shown. The backend sends no DNI, no CUIL and no one who authorized; `autorizadoEn` is not needed
 * and anything else the server may add is dropped.
 */
export const linkRowSchema = z.object({
  vinculoId: z.string(),
  familiaId: z.string(),
  familiaNombre: z.string(),
  deportistaId: z.string(),
  deportistaNombre: z.string(),
  deportistaApellido: z.string(),
  /** The state of the athlete in the SCHOOL: a different idea from the state of the link. */
  deportistaActivo: z.boolean(),
  estado: linkStateSchema,
  /** Only meaningful in an ACTIVE link: see `isPrincipalLink`. */
  esPrincipal: z.boolean(),
  versionVinculos: z.string().regex(/^[0-9]{1,19}$/),
});
export type LinkRow = z.infer<typeof linkRowSchema>;

/** GET /api/admin/deportistas/{id}/vinculos (VinculosDeDeportista): the revision of the athlete's links and all its links, from ONE snapshot. */
export const athleteLinksSchema = z.object({
  deportistaId: z.string(),
  versionVinculos: z.string().regex(/^[0-9]{1,19}$/),
  vinculos: z.array(linkRowSchema),
});
export type AthleteLinks = z.infer<typeof athleteLinksSchema>;

/** GET /api/admin/familias/{id}/deportistas: the links of ONE family (every state), an unpaged list. */
export const familyAthletesSchema = z.array(linkRowSchema);

/** Whole atomic batch: an incomplete or unknown result is not a confirmed success. */
export const linkBatchResultSchema = z.object({
  resultados: z.array(z.object({ vinculo: linkRowSchema, resultado: z.enum(['CREADO', 'REACTIVADO', 'SIN_CAMBIOS']) })).min(1).max(50),
});
export type LinkBatchResult = z.infer<typeof linkBatchResultSchema>;
