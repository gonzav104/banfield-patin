import { z } from 'zod';

/**
 * `GET /api/{familia|admin}/deportistas/{id}/checklist-documental` (U20). It is PARTIAL by contract (`parcial` is always true
 * and `alcance` names the only thing evaluated: the two sides of the athlete's DNI). There is no federative state, no
 * eligibility and no percentage in it, and this schema refuses to invent one: an unknown state is a contract error.
 */
export const requirementStateSchema = z.enum(['CUMPLIDO', 'EN_REVISION', 'OBSERVADO', 'FALTA']);
export type RequirementState = z.infer<typeof requirementStateSchema>;

export const requirementSchema = z.object({
  tipo: z.string(),
  estado: requirementStateSchema,
  motivoObservacion: z.string().nullable().optional(),
});
export type Requirement = z.infer<typeof requirementSchema>;

export const documentChecklistSchema = z.object({
  deportistaId: z.string(),
  parcial: z.literal(true),
  alcance: z.string(),
  // A checklist always evaluates something: an empty list is a contract error, never «everything is approved».
  requisitos: z.array(requirementSchema).min(1),
});
export type DocumentChecklist = z.infer<typeof documentChecklistSchema>;
