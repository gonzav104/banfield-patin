import { z } from 'zod';
import { pageSchema } from '@/features/invitations/schemas';

/** Values accepted by `?estado=` of GET /api/admin/familias/listado. `TODOS` is the backend default. */
export const familyStatusFilterSchema = z.enum(['TODOS', 'ACTIVOS', 'INACTIVOS']);
export type FamilyStatusFilter = z.infer<typeof familyStatusFilterSchema>;

/** One row of the administrative list. By contract it carries no DNI and no contact data. */
export const familyAdminSummarySchema = z.object({
  id: z.string(),
  nombreReferencia: z.string(),
  activa: z.boolean(),
  cantidadTutores: z.number().int().nonnegative(),
  cantidadDeportistasActivos: z.number().int().nonnegative(),
});
export type FamilyAdminSummary = z.infer<typeof familyAdminSummarySchema>;

export const familyAdminPageSchema = pageSchema(familyAdminSummarySchema);
export type FamilyAdminPage = z.infer<typeof familyAdminPageSchema>;

/** A tutor as it appears INSIDE a family detail: the DNI arrives already masked (and may be absent). */
export const familyTutorSchema = z.object({
  id: z.string(),
  nombre: z.string(),
  apellido: z.string(),
  dniEnmascarado: z.string().nullable().optional(),
  telefono: z.string().nullable().optional(),
  email: z.string().nullable().optional(),
  parentesco: z.string().nullable().optional(),
  activo: z.boolean(),
});
export type FamilyTutor = z.infer<typeof familyTutorSchema>;

/**
 * GET/POST /api/admin/familias[/{id}]. `version` (a decimal string) is validated but never shown: it is the optimistic
 * revision that the edit unit will send back; this unit is read-only.
 */
export const familyDetailSchema = z.object({
  id: z.string(),
  nombreReferencia: z.string(),
  activa: z.boolean(),
  tutores: z.array(familyTutorSchema),
  version: z.string().regex(/^[0-9]{1,19}$/),
});
export type FamilyDetail = z.infer<typeof familyDetailSchema>;
