import { z } from 'zod';
import { pageSchema } from '@/features/invitations/schemas';
import { responsableTipoSchema } from '@/features/responsables/tipo';

/** Why a responsibility was closed. Closed enum: an unknown value is a malformed answer, never drawn as if it were a known cause. */
export const closureCauseSchema = z.enum(['REVOCACION_ADMIN', 'TUTOR_INACTIVO', 'FAMILIA_INACTIVA', 'DEPORTISTA_INACTIVO', 'FAMILIA_DEPORTISTA_REVOCADA']);
export type ClosureCause = z.infer<typeof closureCauseSchema>;

/**
 * One responsibility (VinculoTutorRespuesta). The `version` is a decimal string within BIGINT, kept in the private query model and never
 * shown. The backend sends no DNI, contacts, accounts or idempotency data; anything else it may add is dropped here.
 */
export const responsableSchema = z.object({
  id: z.string(),
  familiaId: z.string(),
  tutorId: z.string(),
  nombreTutor: z.string(),
  apellidoTutor: z.string(),
  tipo: responsableTipoSchema.nullable(),
  creadoEn: z.string(),
  cerradoEn: z.string().nullable(),
  causaCierre: closureCauseSchema.nullable(),
  version: z.string().regex(/^[0-9]{1,19}$/),
});
export type Responsable = z.infer<typeof responsableSchema>;

export const responsablesPageSchema = pageSchema(responsableSchema);
export type ResponsablesPage = z.infer<typeof responsablesPageSchema>;
